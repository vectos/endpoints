package endpoints.akkahttp.client

import akka.http.scaladsl.model._
import akka.stream.Materializer
import endpoints.algebra.{Decoder, Encoder, MuxRequest}
import endpoints.{Tupler, algebra}

import scala.concurrent.{ExecutionContext, Future}

class Endpoints(val settings: EndpointsSettings)
               (implicit val EC: ExecutionContext, val M: Materializer) extends algebra.Endpoints with Urls with Methods {
  type RequestHeaders[A] = (A, List[HttpHeader]) => List[HttpHeader]

  lazy val emptyHeaders: RequestHeaders[Unit] = (_, req) => req

  type Request[A] = A => Future[HttpResponse]

  type RequestEntity[A] = (A, HttpRequest) => HttpRequest

  lazy val emptyRequest: RequestEntity[Unit] = (_, req) => req

  def request[A, B, C, AB](
                            method: Method, url: Url[A],
                            entity: RequestEntity[B], headers: RequestHeaders[C]
                          )(implicit tuplerAB: Tupler.Aux[A, B, AB], tuplerABC: Tupler[AB, C]): Request[tuplerABC.Out] =
    (abc: tuplerABC.Out) => {
      val (ab, c) = tuplerABC.unapply(abc)
      val (a, b) = tuplerAB.unapply(ab)
      val uri =
        if (settings.baseUri == Uri("/")) Uri(url.encode(a))
        else Uri(s"${settings.baseUri.path}${url.encode(a)}")

      val request = method(entity(b, HttpRequest(uri = uri)))
        .withHeaders(headers(c, List.empty))

      settings.requestExecutor(request)
    }

  type Response[A] = HttpResponse => Future[Either[Throwable, A]]

  val emptyResponse: Response[Unit] = _ => Future.successful(Right(()))

  val textResponse: Response[String] = x =>
    if (x.status == StatusCodes.OK) {
      x.entity.toStrict(settings.toStrictTimeout)
        .map(settings.stringContentExtractor)
        .map(Right.apply)
    } else {
      Future.failed(new Throwable(s"Unexpected status code: ${x.status.intValue()}"))
    }


  type Endpoint[A, B] = A => Future[B]

  def endpoint[A, B](request: Request[A], response: Response[B]): Endpoint[A, B] =
    a =>
      for {
        resp <- request(a)
        result <- response(resp).flatMap(futureFromEither)
      } yield result

  private def futureFromEither[A](errorOrA: Either[Throwable, A]): Future[A] =
    errorOrA match {
      case Left(error) => Future.failed(error)
      case Right(a) => Future.successful(a)
    }

  class MuxEndpoint[Req <: algebra.MuxRequest, Resp, Transport](
                                                                 request: Request[Transport],
                                                                 response: Response[Transport]
                                                               ) {
    def apply(
               req: Req
             )(implicit
               encoder: Encoder[Req, Transport],
               decoder: Decoder[Transport, Resp]
             ): Future[req.Response] =
      request(encoder.encode(req)).flatMap { resp =>
        response(resp).flatMap { t =>
          futureFromEither(t).flatMap(tt =>
            futureFromEither(decoder.decode(tt)).map(_.asInstanceOf[req.Response])
          )
        }
      }
  }

  def muxEndpoint[Req <: MuxRequest, Resp, Transport](
                                                       request: Request[Transport],
                                                       response: Response[Transport]
                                                     ): MuxEndpoint[Req, Resp, Transport] =
    new MuxEndpoint[Req, Resp, Transport](request, response)

}