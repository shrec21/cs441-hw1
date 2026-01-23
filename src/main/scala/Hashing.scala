import java.nio.file.{Files, Path}
import java.security.MessageDigest

object Hashing:

  /** Convert bytes -> lowercase hex string (stable, readable) */
  private def toHex(bytes: Array[Byte]): String =
    bytes.map("%02x".format(_)).mkString

  /** SHA-256 of an in-memory byte array */
  def sha256Bytes(data: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(data)
    toHex(md.digest())

  /** SHA-256 of a UTF-8 string (used for chunkHash) */
  def sha256(text: String): String =
    sha256Bytes(text.getBytes("UTF-8"))

  /** SHA-256 of an entire PDF file (used for pdfHash) */
  def sha256Pdf(p: Path): String =
    sha256Bytes(Files.readAllBytes(p))

  // (Aliases in case other files use the other names)
  def sha256String(s: String): String = sha256(s)
  def sha256File(p: Path): String = sha256Pdf(p)
