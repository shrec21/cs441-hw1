import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

import java.nio.file.Path
import scala.util.Using

object PdfTextExtractor:

  def extractText(pdfPath: Path): String =
    Using.resource(PDDocument.load(pdfPath.toFile)) { document =>
      val stripper = new PDFTextStripper()
      stripper.getText(document)
    }
