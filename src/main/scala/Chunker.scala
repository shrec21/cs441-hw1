object Chunker:

  /** Normalize whitespace so chunking behaves predictably */
  def normalize(s: String): String =
    s.replaceAll("\\s+", " ").trim

  /**
   * Split text into chunks using (maxChars, overlap).
   * Stride = maxChars - overlap.
   */
  def split(text: String, maxChars: Int, overlap: Int): Vector[String] =
    require(maxChars > 0, "maxChars must be > 0")
    require(overlap >= 0, "overlap must be >= 0")
    require(overlap < maxChars, "overlap must be < maxChars")

    val clean = normalize(text)
    if clean.isEmpty then Vector.empty
    else
      val stride = maxChars - overlap

      // Tail-recursive chunk builder (functional, no induction loops in user code)
      @annotation.tailrec
      def go(i: Int, acc: Vector[String]): Vector[String] =
        if i >= clean.length then acc
        else
          val end = math.min(i + maxChars, clean.length)
          val slice = clean.substring(i, end)

          // Try to cut at a sentence boundary near the end for nicer chunks
          val cutIdx = slice.lastIndexWhere(ch => ch == '.' || ch == '\n')
          val piece =
            if cutIdx >= (maxChars * 0.6).toInt then slice.substring(0, cutIdx + 1)
            else slice

          val nextI = i + math.max(1, piece.length - overlap)
          go(nextI, acc :+ piece)

      go(0, Vector.empty)
