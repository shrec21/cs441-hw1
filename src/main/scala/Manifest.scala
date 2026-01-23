import java.nio.file.{Files, Path}
import scala.util.Try

object Manifest:

  /** Minimal record used for delta detection */
  final case class ChunkRecord(
    docId: String,
    chunkId: Int,
    chunkHash: String,
    pdfHash: String
  )

  /**
   * Preferred internal implementation: load manifest from CSV:
   * docId,chunkId,chunkHash,pdfHash
   */
  def load(path: Path): Vector[ChunkRecord] =
    if !Files.exists(path) then Vector.empty
    else
      val lines = Files.readAllLines(path).toArray.toVector.map(_.toString).filter(_.trim.nonEmpty)

      // allow optional header
      val dataLines =
        if lines.nonEmpty && lines.head.toLowerCase.startsWith("docid,") then lines.tail else lines

      dataLines.flatMap { line =>
        val parts = line.split(",", -1).map(_.trim)
        if parts.length < 4 then None
        else
          Try(parts(1).toInt).toOption.map { cid =>
            ChunkRecord(
              docId = parts(0),
              chunkId = cid,
              chunkHash = parts(2),
              pdfHash = parts(3)
            )
          }
      }

  /** Save manifest to CSV */
  def save(path: Path, rows: Vector[ChunkRecord]): Unit =
    Files.createDirectories(path.getParent)
    val header = "docId,chunkId,chunkHash,pdfHash"
    val body = rows.map(r => s"${r.docId},${r.chunkId},${r.chunkHash},${r.pdfHash}")
    Files.write(path, (header +: body).mkString("\n").getBytes("UTF-8"))

  // ---------------------------------------------------------
  // Compatibility layer (HW2Main expects these method names)
  // ---------------------------------------------------------
  def read(path: Path): Vector[ChunkRecord] =
    load(path)

  def write(rows: Vector[ChunkRecord], path: Path): Unit =
    save(path, rows)
