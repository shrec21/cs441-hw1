import java.nio.file.{Files, Paths, Path}
import scala.jdk.CollectionConverters.*
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

@main def hw2(): Unit =
  val log = LoggerFactory.getLogger("HW2Main")
  val cfg = ConfigFactory.load()

  // ----------------------------
  // 1) Read configuration
  // ----------------------------
  val inputDir   = cfg.getString("rag.inputDir")
  val outDir     = cfg.getString("rag.outputDir")
  val host       = cfg.getString("rag.ollama.host")
  val embedModel = cfg.getString("rag.ollama.embedModel")

  val batchSize  = cfg.getInt("rag.embed.batchSize")
  val maxPdfs    = cfg.getInt("rag.maxPdfs")

  val maxChars   = cfg.getInt("rag.chunk.maxChars")
  val overlap    = cfg.getInt("rag.chunk.overlap")

  val manifestPath: Path = Paths.get(cfg.getString("rag.manifestPath"))
  val currentPath: Path  = Paths.get(cfg.getString("rag.publish.currentPointer"))
  val stagingRoot: Path  = Paths.get(cfg.getString("rag.publish.stagingRoot"))
  val reembedOnModelChange = cfg.getBoolean("rag.delta.reembedOnModelChange")

  log.info(
    s"Config: inputDir=$inputDir outDir=$outDir batchSize=$batchSize maxPdfs=$maxPdfs " +
      s"chunk.maxChars=$maxChars chunk.overlap=$overlap embedModel=$embedModel host=$host"
  )

  // ----------------------------
  // 2) Build list of PDFs
  // ----------------------------
  log.info(s"Scanning PDF directory: $inputDir")

  val pdfsAll: Vector[Path] =
    Files.list(Paths.get(inputDir))
      .iterator().asScala
      .filter(p => p.toString.toLowerCase.endsWith(".pdf"))
      .toVector
      .sortBy(_.getFileName.toString)

  val pdfs: Vector[Path] =
    if maxPdfs > 0 then pdfsAll.take(maxPdfs) else pdfsAll

  log.info(s"Found ${pdfsAll.size} PDFs, processing ${pdfs.size} PDFs (maxPdfs=$maxPdfs)")

  // ----------------------------
  // Helper functions
  // ----------------------------
  def copyDirectory(source: Path, target: Path): Unit =
    Files.createDirectories(target)
    Files.walk(source).forEach { sourcePath =>
      val targetPath = target.resolve(source.relativize(sourcePath))
      if Files.isDirectory(sourcePath) then
        Files.createDirectories(targetPath)
      else
        Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

  def deleteDirectory(dir: Path): Unit =
    if Files.exists(dir) then
      Files.walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete)

  // ----------------------------
  // 3) Create Ollama client
  // ----------------------------
  val client = new OllamaClient(host)

  // ----------------------------
  // 4) Helper: safe embedding with retry + fallback
  // ----------------------------
  def safeEmbedBatch(
    batch: Vector[String],
    model: String,
    retries: Int,
    logPrefix: String
  ): Vector[Vector[Float]] =

    def allEmpty(vs: Vector[Vector[Float]]): Boolean =
      vs.nonEmpty && vs.forall(_.isEmpty)

    var attempt = 1
    while attempt <= retries do
      val vecs = client.embed(batch, model)
      if !allEmpty(vecs) then
        return vecs
      else
        val sleepMs = 300L * attempt
        log.warn(s"$logPrefix Ollama returned empty embeddings (attempt $attempt/$retries). Retrying after ${sleepMs}ms...")
        Thread.sleep(sleepMs)
        attempt += 1

    // Still empty after retries -> fallback to single embeds
    log.warn(s"$logPrefix Fallback to single-embedding per chunk (slow path)")
    batch.map(t => client.embed(Vector(t), model).head)

  // ----------------------------
  // 5) Scan PDFs → Extract → Chunk → Hash
  // ----------------------------
  log.info("Phase 1: Extracting, chunking, and hashing PDFs...")

  def looksLikeReferences(s: String): Boolean =
    val t = s.toLowerCase
    t.contains("references") ||
    t.contains("bibliography") ||
    t.count(_ == '[') >= 8 ||
    t.contains("proceedings") && t.contains("acm") ||
    t.contains("pp.") && t.contains("conference")

  def looksLikeGarbage(s: String): Boolean =
    val t = s.trim
    t.length < 200 ||
    t.count(_.isLetter).toDouble / t.length < 0.3

  // Build current snapshot: Map[(docId, chunkId) -> (chunkText, chunkHash, pdfHash)]
  var currentChunks: Map[(String, Int), (String, String, String)] = Map.empty
  var allChunkRecords: Vector[Manifest.ChunkRecord] = Vector.empty

  pdfs.foreach { pdf =>
    val docId = pdf.getFileName.toString
    log.info(s"Processing: $docId")

    // Hash PDF file
    val pdfHash = Hashing.sha256Pdf(pdf)

    // Extract text
    val text = PdfTextExtractor.extractText(pdf)

    // Chunk
    val rawChunks: Vector[String] = Chunker.split(text, maxChars, overlap)
    val chunks: Vector[String] =
      rawChunks.filterNot(c => looksLikeReferences(c) || looksLikeGarbage(c))

    log.info(s"  Extracted ${chunks.size} chunks from $docId")

    // Hash each chunk and build records
    chunks.zipWithIndex.foreach { case (chunkText, chunkId) =>
      val chunkHash = Hashing.sha256(chunkText)
      val key = (docId, chunkId)
      currentChunks = currentChunks + (key -> (chunkText, chunkHash, pdfHash))
      allChunkRecords = allChunkRecords :+ Manifest.ChunkRecord(docId, chunkId, chunkHash, pdfHash)
    }
  }

  log.info(s"Phase 1 complete: ${allChunkRecords.size} total chunks from ${pdfs.size} PDFs")

  // ----------------------------
  // 6) Delta Plan: Compare current vs previous manifest
  // ----------------------------
  log.info("Phase 2: Computing delta plan...")

  val previousManifest = Manifest.read(manifestPath)
  log.info(s"Read ${previousManifest.size} chunks from previous manifest")

  val chunksToEmbed = DeltaPlanner.plan(currentChunks, previousManifest)
  log.info(s"Delta plan: ${chunksToEmbed.size} chunks need embedding (${allChunkRecords.size - chunksToEmbed.size} unchanged)")

  if chunksToEmbed.isEmpty then
    log.info("No chunks need embedding. Updating manifest and exiting.")
    Manifest.write(allChunkRecords, manifestPath)
    log.info("Manifest updated. Exiting.")
    return

  // ----------------------------
  // 7) Embed only delta chunks
  // ----------------------------
  log.info("Phase 3: Embedding delta chunks...")

  // Initialize Lucene indexer (need to probe dimension first)
  val indexerDim = client.embed(Vector("dim-probe"), embedModel).head.length

  // Build staging index directory
  val stagingIndexDir: Path = stagingRoot.resolve("lucene-index")
  
  // If previous index exists, copy it to staging; otherwise start fresh
  val currentIndexDirOpt: Option[Path] =
    if Files.exists(currentPath) then
      val currentIndexPath = Paths.get(Files.readString(currentPath).trim)
      if Files.exists(currentIndexPath) then
        log.info(s"Copying existing index from $currentIndexPath to staging...")
        // Delete staging if it exists, then copy
        if Files.exists(stagingIndexDir) then
          deleteDirectory(stagingIndexDir)
        copyDirectory(currentIndexPath, stagingIndexDir)
        Some(currentIndexPath)
      else
        None
    else
      None

  val indexer = new LuceneIndexer(stagingIndexDir, indexerDim, append = currentIndexDirOpt.isDefined)
  log.info(s"Lucene indexer initialized with vectorDim=$indexerDim at $stagingIndexDir")

  // Embed chunks in batches
  val totalBatches = Math.ceil(chunksToEmbed.size.toDouble / batchSize).toInt
  var embeddedCount = 0
  var skippedCount = 0

  Batching.batches[DeltaPlanner.ChunkToEmbed](chunksToEmbed, batchSize).zipWithIndex.foreach { case (batch, bi) =>
    val batchNo = bi + 1
    log.info(s"Embedding batch $batchNo/$totalBatches (${batch.size} chunks)")

    val texts = batch.map(_.chunkText)
    val vecs: Vector[Vector[Float]] =
      safeEmbedBatch(
        batch = texts,
        model = embedModel,
        retries = 3,
        logPrefix = s"batch=$batchNo"
      )

    // Add to Lucene (delete old version first if it's a changed chunk)
    batch.zip(vecs).foreach { case (chunk, vec) =>
      if vec.isEmpty then
        skippedCount += 1
        log.warn(s"Skipping empty embedding: doc=${chunk.docId} chunk=${chunk.chunkId}")
      else if vec.length != indexerDim then
        skippedCount += 1
        log.warn(s"Skipping dim mismatch: doc=${chunk.docId} chunk=${chunk.chunkId} got=${vec.length} expected=$indexerDim")
      else
        // Delete old version if it exists (for changed chunks)
        if currentIndexDirOpt.isDefined then
          indexer.delete(chunk.docId, chunk.chunkId)
        // Add new version
        indexer.add(chunk.docId, chunk.chunkId, chunk.chunkText, vec)
        embeddedCount += 1
    }
  }

  indexer.close()
  log.info(s"Phase 3 complete: embedded $embeddedCount chunks, skipped $skippedCount")

  // ----------------------------
  // 8) Publish atomically: Write CURRENT and manifest
  // ----------------------------
  log.info("Phase 4: Publishing index atomically...")

  // Final index directory name (with timestamp for uniqueness)
  val timestamp = System.currentTimeMillis()
  val finalIndexDir: Path = Paths.get(outDir).resolve(s"lucene-index-$timestamp")
  
  // Move staging to final location
  if Files.exists(stagingIndexDir) then
    Files.move(stagingIndexDir, finalIndexDir)
    log.info(s"Moved staging index to $finalIndexDir")

  // Write CURRENT file atomically (points to final index)
  val tempCurrent = Paths.get(outDir).resolve("CURRENT.tmp")
  Files.writeString(tempCurrent, finalIndexDir.toString)
  Files.move(tempCurrent, currentPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  log.info(s"Wrote CURRENT -> $finalIndexDir")

  // Write new manifest
  Manifest.write(allChunkRecords, manifestPath)
  log.info(s"Wrote manifest with ${allChunkRecords.size} chunks")

  // Cleanup old index directories (keep only the current one)
  if currentIndexDirOpt.isDefined && currentIndexDirOpt.get != finalIndexDir then
    log.info(s"Cleaning up old index: ${currentIndexDirOpt.get}")
    deleteDirectory(currentIndexDirOpt.get)

  log.info("Phase 4 complete. Index published successfully!")
