object ContextPacker:

  /**
   * Build a single context string from retrieved chunks.
   *
   * We:
   * - deduplicate by (docId, chunkId)
   * - keep topK unique chunks
   * - label each chunk so the LLM can cite it
   */
  def pack(results: Vector[(Float, String, Int, String)], topK: Int, maxCharsPerChunk: Int = 800): String =
    if results.isEmpty then
      return ""
    
    val unique =
      results
        .groupBy { case (_, docId, chunkId, _) => (docId, chunkId) }
        .values
        .map(_.head)                 // keep first occurrence
        .toVector
        .sortBy { case (score, _, _, _) => -score } // sort descending score
        .take(topK)

    if unique.isEmpty then
      return ""

    val blocks =
      unique.map { case (score, docId, chunkId, text) =>
        val snippet = text.take(maxCharsPerChunk).replaceAll("\\s+", " ").trim
        if snippet.nonEmpty then
          s"--- Source: $docId (chunk $chunkId, relevance: ${"%.3f".format(score)}) ---\n$snippet"
        else
          ""
      }.filter(_.nonEmpty)

    blocks.mkString("\n\n")
