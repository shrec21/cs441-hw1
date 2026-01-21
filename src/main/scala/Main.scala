import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

import java.nio.file.Paths

@main def run(): Unit =
  val log = LoggerFactory.getLogger("Main")
  val cfg = ConfigFactory.load()

  val inputDir = cfg.getString("rag.inputDir")
  log.info(s"Scanning PDF directory: $inputDir")

  val pdfs =
    Files.list(Paths.get(inputDir))
      .filter(p => p.toString.endsWith(".pdf"))
      .iterator()
      .asScala
      .toList

  if pdfs.isEmpty then
    log.warn("No PDF files found")
  else
    val pdf = pdfs.head
    log.info(s"Extracting text from: ${pdf.getFileName}")

    val text = PdfTextExtractor.extractText(pdf)
    log.info(s"Extracted ${text.length} characters from PDF")

    val maxChars = cfg.getInt("rag.chunk.maxChars")
    val overlap  = cfg.getInt("rag.chunk.overlap")

    val chunks = Chunker.split(text, maxChars, overlap)
    log.info(s"Chunked into ${chunks.size} chunks (maxChars=$maxChars overlap=$overlap)")

    // Print a small preview of chunk #0 and #1
    chunks.headOption.foreach(c => println("\n--- CHUNK 0 ---\n" + c.take(500)))
    chunks.lift(1).foreach(c => println("\n--- CHUNK 1 ---\n" + c.take(500)))

    val outDir = cfg.getString("rag.outputDir")
    val vocabPath = Paths.get(outDir).resolve("vocab.csv")

    val counts = VocabCounter.countWords(chunks)
    log.info(s"Vocabulary size (unique words): ${counts.size}")

    VocabCounter.writeCsv(counts, vocabPath)
    log.info(s"Wrote vocab CSV to: $vocabPath")

    val host  = cfg.getString("rag.ollama.host")
    val model = cfg.getString("rag.ollama.embedModel")

    val client = new OllamaClient(host)

    // Embed only first 5 chunks for now (fast test)
    val sample = chunks.take(5)

    log.info(s"Embedding ${sample.size} chunks using model=$model at host=$host")
    val vectors = client.embed(sample, model)
    log.info(s"Requested ${sample.size} embeddings, received ${vectors.size}")
    log.info(s"First vector length = ${vectors.headOption.map(_.size).getOrElse(0)}")


    log.info(s"Got ${vectors.size} embeddings. Vector dimension = ${vectors.headOption.map(_.size).getOrElse(0)}")



