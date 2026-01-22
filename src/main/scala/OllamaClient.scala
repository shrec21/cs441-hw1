import sttp.client3.*
import sttp.client3.circe.*
import io.circe.*
import io.circe.generic.semiauto.*

/** -------- Embeddings (new endpoint: /api/embed) -------- */
final case class EmbedBatchReq(model: String, input: Vector[String])
final case class EmbedBatchResp(embeddings: Vector[Vector[Float]])

/** -------- Embeddings (old endpoint: /api/embeddings) -------- */
final case class EmbedSingleReq(model: String, input: String)
final case class EmbedSingleResp(embedding: Vector[Float])

/** -------- Chat (/api/chat) -------- */
final case class ChatMessage(role: String, content: String)
final case class ChatReq(model: String, messages: Vector[ChatMessage], stream: Boolean = false)
final case class ChatResp(message: ChatMessage)

/**
 * OllamaClient talks to Ollama's HTTP API:
 * - embed(texts, model) returns vectors (one per input)
 * - chat(messages, model) returns assistant text
 */
class OllamaClient(baseUrl: String):

  // One HTTP backend reused for all calls
  private val backend = HttpClientSyncBackend()

  // Endpoints
  private val embedNewUrl = uri"$baseUrl/api/embed"
  private val embedOldUrl = uri"$baseUrl/api/embeddings"
  private val chatUrl     = uri"$baseUrl/api/chat"

  // --- Circe JSON codecs (explicit type params avoid “ambiguous given” issues) ---
  private given Encoder[EmbedBatchReq]  = deriveEncoder[EmbedBatchReq]
  private given Decoder[EmbedBatchResp] = deriveDecoder[EmbedBatchResp]

  private given Encoder[EmbedSingleReq]  = deriveEncoder[EmbedSingleReq]
  private given Decoder[EmbedSingleResp] = deriveDecoder[EmbedSingleResp]

  private given Encoder[ChatMessage] = deriveEncoder[ChatMessage]
  private given Decoder[ChatMessage] = deriveDecoder[ChatMessage]
  private given Encoder[ChatReq]     = deriveEncoder[ChatReq]
  private given Decoder[ChatResp]    = deriveDecoder[ChatResp]

  /**
   * Embed a batch of texts.
   * Tries /api/embed (batch) first; if it fails, falls back to /api/embeddings (single per text).
   */
  def embed(texts: Vector[String], model: String): Vector[Vector[Float]] =
    if texts.isEmpty then Vector.empty
    else
      // 1) New batch endpoint
      val batchReq =
        basicRequest
          .post(embedNewUrl)
          .body(EmbedBatchReq(model = model, input = texts))
          .response(asJson[EmbedBatchResp])

      batchReq.send(backend).body match
        case Right(r) =>
          r.embeddings
        case Left(_) =>
          // 2) Fallback: old endpoint, one request per text
          texts.map { t =>
            val singleReq =
              basicRequest
                .post(embedOldUrl)
                .body(EmbedSingleReq(model = model, input = t))
                .response(asJson[EmbedSingleResp])

            singleReq.send(backend).body match
              case Right(one) => one.embedding
              case Left(e)    => throw new RuntimeException(s"Ollama embed failed: $e")
          }

  /**
   * Call an Ollama chat model and return the assistant response text.
   */
  def chat(messages: Vector[ChatMessage], model: String): String =
    val req =
      basicRequest
        .post(chatUrl)
        .body(ChatReq(model = model, messages = messages, stream = false))
        .response(asJson[ChatResp])

    req.send(backend).body match
      case Right(r) => r.message.content
      case Left(e)  => throw new RuntimeException(s"Ollama chat failed: $e")
