import sttp.client3.*
import sttp.client3.circe.*
import io.circe.*
import io.circe.generic.semiauto.*

/** Request for newer Ollama batch endpoint: POST /api/embed */
final case class EmbedBatchReq(model: String, input: Vector[String])

/** Response for newer endpoint typically contains: { "embeddings": [[...], ...] } */
final case class EmbedBatchResp(embeddings: Vector[Vector[Float]])

/** Request for older endpoint: POST /api/embeddings (often single) */
final case class EmbedSingleReq(model: String, input: String)

/** Older endpoint sometimes returns: { "embedding": [...] } */
final case class EmbedSingleResp(embedding: Vector[Float])

object OllamaJson:
  given Encoder[EmbedBatchReq]  = deriveEncoder
  given Decoder[EmbedBatchResp] = deriveDecoder

  given Encoder[EmbedSingleReq]  = deriveEncoder
  given Decoder[EmbedSingleResp] = deriveDecoder

class OllamaClient(baseUrl: String):

  import OllamaJson.given

  private val backend  = HttpClientSyncBackend()
  private val embedNew = uri"$baseUrl/api/embed"
  private val embedOld = uri"$baseUrl/api/embeddings"

  /**
   * Embed a batch of texts. Tries /api/embed first (batch), falls back to /api/embeddings (single).
   */
  def embed(texts: Vector[String], model: String): Vector[Vector[Float]] =
    if texts.isEmpty then Vector.empty
    else
      // 1) Try newer batch endpoint
      val batchReq =
        basicRequest
          .post(embedNew)
          .body(EmbedBatchReq(model = model, input = texts))
          .response(asJson[EmbedBatchResp])

      val batchResp = batchReq.send(backend).body
      batchResp match
        case Right(ok) => ok.embeddings
        case Left(_)   =>
          // 2) Fall back to older endpoint: call once per text
          texts.map { t =>
            val singleReq =
              basicRequest
                .post(embedOld)
                .body(EmbedSingleReq(model = model, input = t))
                .response(asJson[EmbedSingleResp])

            singleReq.send(backend).body match
              case Right(one) => one.embedding
              case Left(e)    => throw new RuntimeException(s"Ollama embed failed: $e")
          }
