import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.*
import org.apache.lucene.index.{IndexWriter, IndexWriterConfig}
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.VectorSimilarityFunction

import java.nio.file.Path

class LuceneIndexer(indexDir: Path, vectorDim: Int):

  // Analyzer is used for classic text fields (BM25-style search).
  // Even though we mainly use vector search, storing text with an analyzer is useful.
  private val analyzer = new StandardAnalyzer()

  // IndexWriterConfig controls how Lucene writes index files.
  private val config = new IndexWriterConfig(analyzer)

  // FSDirectory = store index on disk at indexDir
  private val writer =
    new IndexWriter(FSDirectory.open(indexDir), config)

  /**
   * Add one chunk to the index.
   *
   * @param docId   PDF identifier (e.g., filename)
   * @param chunkId Chunk number within the document
   * @param text    Chunk text
   * @param vec     Embedding vector (length = vectorDim)
   */
  def add(docId: String, chunkId: Int, text: String, vec: Vector[Float]): Unit =
    require(vec.length == vectorDim, s"Vector dim mismatch: expected $vectorDim")

    val doc = new Document()

    // Metadata fields (exact match, not tokenized)
    doc.add(new StringField("doc_id", docId, Field.Store.YES))
    doc.add(new StringField("chunk_id", chunkId.toString, Field.Store.YES))

    // Store chunk text so we can show it when retrieved
    doc.add(new StoredField("text", text))

    // Vector field for kNN search (HNSW under the hood)
    doc.add(
      new KnnFloatVectorField(
        "vec",
        vec.toArray,
        VectorSimilarityFunction.COSINE
      )
    )

    writer.addDocument(doc)

  /** Commit changes and close the index. */
  def close(): Unit =
    writer.commit()
    writer.close()
