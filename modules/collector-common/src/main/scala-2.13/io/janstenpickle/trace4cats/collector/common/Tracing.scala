package io.janstenpickle.trace4cats.collector.common

import java.net.InetAddress

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.{Concurrent, Sync, Timer}
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.syntax.semigroup._
import fs2.Pipe
import io.chrisdavenport.log4cats.Logger
import io.janstenpickle.trace4cats.`export`.StreamSpanExporter
import io.janstenpickle.trace4cats.collector.common.config.{KafkaListenerConfig, ListenerConfig, TracingConfig}
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.meta.{PipeTracer, TracedSpanExporter}
import io.janstenpickle.trace4cats.model.AttributeValue.StringList
import io.janstenpickle.trace4cats.model.{AttributeValue, CompletedSpan, TraceProcess}
import io.janstenpickle.trace4cats.rate.sampling.RateSpanSampler

object Tracing {
  def process[F[_]: Sync]: F[TraceProcess] = Sync[F].delay(InetAddress.getLocalHost.getHostName).map { hostname =>
    TraceProcess("trace4cats-collector", Map("hostname" -> hostname))
  }

  def sampler[F[_]: Concurrent: Timer](config: Option[TracingConfig], bufferSize: Int): F[Option[SpanSampler[F]]] =
    config.traverse(
      _.sampleRate.fold(Applicative[F].pure(SpanSampler.always[F]))(rate => RateSpanSampler.create[F](bufferSize, rate))
    )

  def pipe[F[_]: Concurrent: Timer](
    sampler: Option[SpanSampler[F]],
    process: TraceProcess,
    listener: ListenerConfig,
    kafka: Option[KafkaListenerConfig],
  ): Pipe[F, CompletedSpan, CompletedSpan] =
    sampler match {
      case None => identity
      case Some(sampler) =>
        PipeTracer[F](
          Map[String, AttributeValue](
            "listen.protocols" -> AttributeValue.StringList(
              NonEmptyList("tcp", List("udp") ++ kafka.map(_ => "kafka"))
            ),
            "listen.format" -> "avro",
            "listen.port" -> listener.port
          ) ++ kafka.toList.flatMap { kafkaConfig =>
            List[(String, AttributeValue)](
              "bootstrap.servers" -> AttributeValue.StringList(kafkaConfig.bootstrapServers),
              "topic" -> kafkaConfig.topic,
              "consumer.group" -> kafkaConfig.group
            )
          }.toMap,
          process,
          sampler
        )
    }

  def combineAttributes(allAttrs: List[(String, AttributeValue)]): Map[String, AttributeValue] =
    allAttrs.groupMapReduce(_._1)(_._2)(_ |+| _)

  def exporter[F[_]: Concurrent: Timer: Logger](
    sampler: Option[SpanSampler[F]],
    name: String,
    exporterNames: List[String],
    attributes: List[(String, AttributeValue)],
    process: TraceProcess,
    exporter: StreamSpanExporter[F],
  ): StreamSpanExporter[F] =
    sampler match {
      case None => exporter
      case Some(sampler) =>
        TracedSpanExporter[F](
          name,
          combineAttributes(attributes) ++ NonEmptyList
            .fromList(exporterNames.distinct)
            .map(names => "exporter.names" -> StringList(names))
            .toMap,
          process,
          sampler,
          exporter
        )
    }
}
