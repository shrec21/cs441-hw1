object Batching:
  def batches[A](xs: Vector[A], n: Int): Vector[Vector[A]] =
    xs.grouped(n).map(_.toVector).toVector
