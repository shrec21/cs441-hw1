import java.nio.file.{Files, Paths, Path}
import scala.jdk.CollectionConverters.*
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

@main def run(): Unit =
  val log = LoggerFactory.getLogger("Main")
  val cfg = ConfigFactory.load()

  // ----------------------------
  // 1) Read configuration
  // ----------------------------
  val inputDir   = cfg.getString("rag.inputDir")
  val outDir     = cfg.getString("rag.outputDir")
  val host       = cfg.getString("rag.ollama.host")
  val embedModel = cfg.getString("rag.ollama.embedModel")
  val chatModel  = cfg.getString("rag.ollama.chatModel")

  val batchSize  = cfg.getInt("rag.embed.batchSize")
  val maxPdfs    = cfg.getInt("rag.maxPdfs")

  val maxChars   = cfg.getInt("rag.chunk.maxChars")
  val overlap    = cfg.getInt("rag.chunk.overlap")

  val indexDir: Path = Paths.get(outDir).resolve("lucene-index")

  log.info(
    s"Config: inputDir=$inputDir outDir=$outDir batchSize=$batchSize maxPdfs=$maxPdfs " +
      s"chunk.maxChars=$maxChars chunk.overlap=$overlap embedModel=$embedModel chatModel=$chatModel host=$host"
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

  log.info(s"Found ${pdfsAll.size} PDFs, indexing ${pdfs.size} PDFs (maxPdfs=$maxPdfs)")

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
  // 5) Indexing state
  // ----------------------------
  var indexerOpt: Option[LuceneIndexer] = None
  var indexerDim: Int = -1

  var totalDocs   = 0
  var totalChunks = 0
  var skippedChunks = 0

  // ----------------------------
  // 6) Index ALL PDFs (extract -> chunk -> embed -> add)
  // ----------------------------
  pdfs.foreach { pdf =>
    totalDocs += 1
    val docId = pdf.getFileName.toString

    log.info(s"[$totalDocs/${pdfs.size}] Extracting text: $docId")
    val text = PdfTextExtractor.extractText(pdf)

    val chunks: Vector[String] = Chunker.split(text, maxChars, overlap)
    log.info(s"[$totalDocs/${pdfs.size}] Chunked into ${chunks.size} chunks")
    totalChunks += chunks.size

    // Initialize Lucene indexer once (lock vector dimension)
    if indexerOpt.isEmpty then
      indexerDim = client.embed(Vector("dim-probe"), embedModel).head.length
      indexerOpt = Some(new LuceneIndexer(indexDir, indexerDim))
      log.info(s"Lucene indexer initialized with vectorDim=$indexerDim at $indexDir")

    val indexer = indexerOpt.get

    // Embed chunks in batches
    val totalBatches = Math.ceil(chunks.size.toDouble / batchSize).toInt

    Batching.batches[String](chunks, batchSize).zipWithIndex.foreach { case (batch, bi) =>
      val batchNo = bi + 1
      log.info(s"Embedding batch $batchNo/$totalBatches for $docId")

      val vecs: Vector[Vector[Float]] =
        safeEmbedBatch(
          batch = batch,
          model = embedModel,
          retries = 3,
          logPrefix = s"doc=$docId batch=$batchNo"
        )

      val dims = vecs.map(_.length).distinct
      log.info(s"Received ${vecs.size} embeddings for $docId batch=$batchNo, dims=$dims")

      // Add to Lucene (skip bad vectors instead of crashing)
      batch.zip(vecs).zipWithIndex.foreach { case ((chunkText, vec), ciInBatch) =>
        val globalChunkId = bi * batchSize + ciInBatch

        if vec.isEmpty then
          skippedChunks += 1
          log.warn(s"Skipping empty embedding: doc=$docId chunk=$globalChunkId")
        else if vec.length != indexerDim then
          skippedChunks += 1
          log.warn(s"Skipping dim mismatch: doc=$docId chunk=$globalChunkId got=${vec.length} expected=$indexerDim")
        else
          indexer.add(docId, globalChunkId, chunkText, vec)
      }
    }
  }

  // Close index at end
  indexerOpt.foreach(_.close())
  log.info(s"Indexing complete. docs=$totalDocs chunks=$totalChunks skippedChunks=$skippedChunks indexDir=$indexDir")

  // ----------------------------
  // 7) Demo: Query -> Retrieve -> Pack context -> Chat (RAG)
  // ----------------------------
  val queryText = "How do they estimate development effort in OpenStack?"
  val qVec: Vector[Float] = client.embed(Vector(queryText), embedModel).head

  val results = LuceneSearcher.search(indexDir, qVec, topK = 5)
  val context = ContextPacker.pack(results, topK = 3, maxCharsPerChunk = 500)

  println("\n====== RAG CONTEXT BLOCK ======\n")
  println(context)
  println("\n===============================\n")

  val systemPrompt =
    """You are an AI assistant.
      |Answer the question using ONLY the provided context.
      |If the answer is not contained in the context, say "I don't know."
      |""".stripMargin

  val messages = Vector(
    ChatMessage("system", systemPrompt),
    ChatMessage("user", s"Context:\n$context\n\nQuestion:\n$queryText")
  )

  log.info(s"Asking chat model: $chatModel")
  val answer = client.chat(messages, chatModel)

  println("\n====== RAG ANSWER ======\n")
  println(answer)
  println("\n========================\n")
