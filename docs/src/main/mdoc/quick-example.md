---
id: quick-example
title: Quick Example
---

Following is an example showing how to:

- use `consumerStream` in order to stream records from Kafka,
- use `producerStream` to produce newly created records to Kafka,
- use `commitBatchWithinF` to commit consumed offsets in batches.

```scala mdoc
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.functor._
import fs2.kafka._
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import scala.concurrent.duration._

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    def processRecord(record: ConsumerRecord[String, String]): IO[(String, String)] =
      IO.pure(record.key -> record.value)

    val consumerSettings =
      ConsumerSettings(
        keyDeserializer = new StringDeserializer,
        valueDeserializer = new StringDeserializer
      )
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withBootstrapServers("localhost")
      .withGroupId("group")

    val producerSettings =
      ProducerSettings(
        keySerializer = new StringSerializer,
        valueSerializer = new StringSerializer
      )
      .withBootstrapServers("localhost")

    val stream =
      producerStream[IO]
        .using(producerSettings)
        .flatMap { producer =>
          consumerStream[IO]
            .using(consumerSettings)
            .evalTap(_.subscribeTo("topic"))
            .flatMap(_.stream)
            .mapAsync(25) { message =>
              processRecord(message.record)
                .map { case (key, value) =>
                  val record = new ProducerRecord("topic", key, value)
                  ProducerMessage.single(record, message.committableOffset)
                }
            }
            .evalMap(producer.produceBatched)
            .map(_.map(_.passthrough))
            .to(commitBatchWithinF(500, 15.seconds))
        }

    stream.compile.drain.as(ExitCode.Success)
  }
}
```
