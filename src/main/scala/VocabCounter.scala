import java.nio.file.{Files, Path}
import scala.util.Using

object VocabCounter:

  /** Tokenize text into "words" in a simple, deterministic way. */
  def tokenize(text: String): Vector[String] =
    text
      .toLowerCase
      .split("[^a-z0-9]+")   // split on anything that is not a-z or 0-9
      .toVector
      .filter(w => w.nonEmpty && w.length >= 2)

  /**
   * Count word frequencies from a collection of chunks.
   * Returns an immutable Map(word -> count).
   */
  def countWords(chunks: Vector[String]): Map[String, Long] =
    chunks
      .flatMap(tokenize)                 // all tokens from all chunks
      .groupBy(identity)                 // Map(word -> Vector(word,word,...))
      .view
      .mapValues(_.size.toLong)          // Map(word -> count)
      .toMap

  /** Write vocab counts to CSV sorted by count descending. */
  def writeCsv(counts: Map[String, Long], outPath: Path): Unit =
    val header = "word,count\n"
    val rows =
      counts.toVector
        .sortBy { case (_, c) => -c }    // descending count
        .map { case (w, c) =>
        val safe = w.replaceAll("[\\r\\n,]", " ")   // prevent CSV breaks
        s"$safe,$c\n"
        }
        .mkString

    val content = header + rows
    Files.createDirectories(outPath.getParent)

    Using.resource(Files.newBufferedWriter(outPath)) { w =>
      w.write(content)
    }
