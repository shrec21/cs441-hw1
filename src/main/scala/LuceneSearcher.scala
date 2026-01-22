import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.{IndexSearcher, KnnFloatVectorQuery}
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.document.Document

import java.nio.file.Path

object LuceneSearcher:

  /**
   * Search the Lucene vector index and return topK (score, docId, chunkId, text).
   *
   * @param indexDir directory where Lucene index was written
   * @param queryVec embedding vector for the user query
   * @param topK number of nearest neighbors to retrieve
   */
  def search(indexDir: Path, queryVec: Vector[Float], topK: Int): Vector[(Float, String, Int, String)] =
    val dir = FSDirectory.open(indexDir)
    val reader = DirectoryReader.open(dir)
    try
      val searcher = new IndexSearcher(reader)

      // kNN query over the "vec" field
      val q = new KnnFloatVectorQuery("vec", queryVec.toArray, topK)
      val hits = searcher.search(q, topK).scoreDocs.toVector

      hits.map { sd =>
        val doc: Document = searcher.doc(sd.doc)
        val docId = doc.get("doc_id")
        val chunkId = doc.get("chunk_id").toInt
        val text = doc.get("text") // stored field
        (sd.score, docId, chunkId, text)
      }
    finally
      reader.close()
      dir.close()
