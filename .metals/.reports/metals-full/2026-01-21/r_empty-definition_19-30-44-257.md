error id: file://<WORKSPACE>/src/main/scala/Main.scala:
file://<WORKSPACE>/src/main/scala/Main.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -scala/jdk/CollectionConverters.text.
	 -scala/jdk/CollectionConverters.text#
	 -scala/jdk/CollectionConverters.text().
	 -text.
	 -text#
	 -text().
	 -scala/Predef.text.
	 -scala/Predef.text#
	 -scala/Predef.text().
offset: 1531
uri: file://<WORKSPACE>/src/main/scala/Main.scala
text:
```scala
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

@main def run(): Unit =
  val log = LoggerFactory.getLogger("Main")
  val cfg = ConfigFactory.load()

  val inputDir  = cfg.getString("rag.inputDir")
  val outDir    = cfg.getString("rag.outputDir")
  val host      = cfg.getString("rag.ollama.host")
  val embedModel= cfg.getString("rag.ollama.embedModel")
  val chatModel = cfg.getString("rag.ollama.chatModel")

  val batchSize = cfg.getInt("rag.embed.batchSize")
  val maxPdfs   = cfg.getInt("rag.maxPdfs")

  val indexDir = Paths.get(outDir).resolve("lucene-index")

  log.info(s"Scanning PDF directory: $inputDir")

  val pdfsAll =
    Files.list(Paths.get(inputDir))
      .iterator().asScala
      .filter(p => p.toString.toLowerCase.endsWith(".pdf"))
      .toVector
      .sortBy(_.getFileName.toString)

  val pdfs =
    if maxPdfs > 0 then pdfsAll.take(maxPdfs) else pdfsAll

  log.info(s"Found ${pdfsAll.size} PDFs, indexing ${pdfs.size} PDFs (maxPdfs=$maxPdfs)")

  val client = new OllamaClient(host)

  // We will open one index writer for the whole corpus
  var indexerOpt: Option[LuceneIndexer] = None

  // Keep counters for logging
  var totalChunks = 0
  var totalDocs   = 0

  pdfs.foreach { pdf =>
    totalDocs += 1
    val docId = pdf.getFileName.toString

    log.info(s"[$totalDocs/${pdfs.size}] Extracting text: $docId")
    val text = PdfTextExtractor.extractText(pdf)
    val chunks = Chunker.split(text@@)

    log.info(s"[$totalDocs/${pdfs.size}] Chunked into ${chunks.size} chunks")
    totalChunks += chunks.size

    // Initialize indexer lazily once we know vector dim
    if indexerOpt.isEmpty then
      // embed one small string to learn dim
      val dim = client.embed(Vector("dim-probe"), embedModel).head.size
      indexerOpt = Some(new LuceneIndexer(indexDir, dim))
      log.info(s"Lucene indexer initialized with vectorDim=$dim at $indexDir")

    val indexer = indexerOpt.get

    // Embed chunks in batches
    Batching.batches(chunks, batchSize).zipWithIndex.foreach { case (batch, bi) =>
      log.info(s"Embedding batch ${bi + 1}/${Math.ceil(chunks.size.toDouble / batchSize).toInt} for $docId")

      val vecs = client.embed(batch, embedModel)

      // Add to index
      batch.zip(vecs).zipWithIndex.foreach { case ((chunkText, vec), ciInBatch) =>
        val globalChunkId = bi * batchSize + ciInBatch
        indexer.add(docId, globalChunkId, chunkText, vec)
      }
    }
  }

  // Close index at end
  indexerOpt.foreach(_.close())
  log.info(s"Indexing complete. docs=$totalDocs chunks=$totalChunks indexDir=$indexDir")

  // ---- Query + RAG generation demo ----
  val queryText = "How do they estimate development effort in OpenStack?"
  val qVec = client.embed(Vector(queryText), embedModel).head
  val results = LuceneSearcher.search(indexDir, qVec, topK = 5)
  val context = ContextPacker.pack(results, topK = 3, maxCharsPerChunk = 500)

  println("\n====== RAG CONTEXT BLOCK ======\n")
  println(context)
  println("\n===============================\n")

  val systemPrompt =
    """You are an AI assistant.
Answer the question using ONLY the provided context.
If the answer is not contained in the context, say "I don't know."
""".stripMargin

  val messages = Vector(
    ChatMessage("system", systemPrompt),
    ChatMessage("user", s"Context:\n$context\n\nQuestion:\n$queryText")
  )

  log.info(s"Asking chat model: $chatModel")
  val answer = client.chat(messages, chatModel)

  println("\n====== RAG ANSWER ======\n")
  println(answer)
  println("\n========================\n")

```


#### Short summary: 

empty definition using pc, found symbol in pc: 