import java.nio.file.Path

/**
 * DeltaPlanner compares current snapshot (extracted/chunked PDFs) vs previous manifest.
 * Returns only chunks that need embedding: new chunks or chunks with changed text.
 */
object DeltaPlanner:

  /**
   * Represents a chunk that needs to be embedded.
   */
  final case class ChunkToEmbed(
    docId: String,
    chunkId: Int,
    chunkText: String,
    chunkHash: String,
    pdfHash: String
  )

  /**
   * Plan which chunks need embedding by comparing current chunks vs previous manifest.
   *
   * @param currentChunks Map from (docId, chunkId) -> (chunkText, chunkHash, pdfHash)
   * @param previousManifest Vector of ChunkRecords from previous run
   * @return Vector of ChunkToEmbed for chunks that are new or changed
   */
  def plan(
    currentChunks: Map[(String, Int), (String, String, String)],
    previousManifest: Vector[Manifest.ChunkRecord]
  ): Vector[ChunkToEmbed] =

    // Build lookup: (docId, chunkId) -> ChunkRecord
    val previousMap: Map[(String, Int), Manifest.ChunkRecord] =
      previousManifest.map(r => (r.docId, r.chunkId) -> r).toMap

    // Find chunks that need embedding
    currentChunks.flatMap { case ((docId, chunkId), (chunkText, chunkHash, pdfHash)) =>
      previousMap.get((docId, chunkId)) match
        case None =>
          // New chunk - needs embedding
          Some(ChunkToEmbed(docId, chunkId, chunkText, chunkHash, pdfHash))
        
        case Some(prevRecord) =>
          // Existing chunk - check if it changed
          if prevRecord.chunkHash != chunkHash || prevRecord.pdfHash != pdfHash then
            // Chunk text or PDF changed - needs re-embedding
            Some(ChunkToEmbed(docId, chunkId, chunkText, chunkHash, pdfHash))
          else
            // Unchanged - skip
            None
    }.toVector
      .sortBy(c => (c.docId, c.chunkId))  // Sort for deterministic output
