/*
 * Copyright 2018-2019 OVO Energy Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2.kafka

import cats.Reducible
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.syntax.concurrent._
import cats.instances.list._
import cats.instances.unit._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.monadError._
import cats.syntax.reducible._
import cats.syntax.semigroup._
import cats.syntax.traverse._
import fs2.concurrent.Queue
import fs2.kafka.internal.KafkaConsumerActor._
import fs2.kafka.internal.instances._
import fs2.kafka.internal.syntax._
import fs2.kafka.internal.{KafkaConsumerActor, Synchronized}
import fs2.{Chunk, Stream}
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

/**
  * [[KafkaConsumer]] represents a consumer of Kafka messages, with the
  * ability to `subscribe` to topics, start a single top-level stream,
  * and optionally control it via the provided [[fiber]] instance.<br>
  * <br>
  * The following top-level streams are provided.<br>
  * <br>
  * - [[stream]] provides a single stream of messages, where the order
  *   of records is guaranteed per topic-partition.<br>
  * - [[partitionedStream]] provides a stream with elements as streams
  *   that continually request records for a single partition. Order
  *   is guaranteed per topic-partition, but all assigned partitions
  *   will have to be processed in parallel.<br>
  * <br>
  * For the streams, records are wrapped in [[CommittableMessage]]s
  * which provide [[CommittableOffset]]s with the ability to commit
  * record offsets to Kafka. For performance reasons, offsets are
  * usually committed in batches using [[CommittableOffsetBatch]].
  * Provided `Sink`s, like [[commitBatch]] or [[commitBatchWithin]]
  * are available for batch committing offsets. If you are not
  * committing offsets to Kafka, you can simply discard the
  * [[CommittableOffset]], and only make use of the record.<br>
  * <br>
  * While it's technically possible to start more than one stream from a
  * single [[KafkaConsumer]], it is generally not recommended as there is
  * no guarantee which stream will receive which records, and there might
  * be an overlap, in terms of duplicate messages, between the two streams.
  * If a first stream completes, possibly with error, there's no guarantee
  * the stream has processed all of the messages it received, and a second
  * stream from the same [[KafkaConsumer]] might not be able to pick up where
  * the first one left off. Therefore, only create a single top-level stream
  * per [[KafkaConsumer]], and if you want to start a new stream if the first
  * one finishes, let the [[KafkaConsumer]] shutdown and create a new one.
  */
sealed abstract class KafkaConsumer[F[_], K, V] {

  /**
    * `Stream` where the elements are Kafka messages and where ordering
    * is guaranteed per topic-partition. Parallelism can be achieved on
    * record-level, using for example `parEvalMap`. For partition-level
    * parallelism, use [[partitionedStream]], where all partitions need
    * to be processed in parallel.<br>
    * <br>
    * The `Stream` works by continually making requests for records on
    * every assigned partition, waiting for records to come back on all
    * partitions, or up to [[ConsumerSettings#fetchTimeout]]. Records can
    * be processed as soon as they are received, without waiting on other
    * partition requests, but a second request for the same partition will
    * wait for outstanding fetches to complete or timeout before being sent.
    *
    * @note you have to first use `subscribe` to subscribe the consumer
    *       before using this `Stream`. If you forgot to subscribe, there
    *       will be a [[NotSubscribedException]] raised in the `Stream`.
    */
  def stream: Stream[F, CommittableMessage[F, K, V]]

  /**
    * `Stream` where the elements themselves are `Stream`s which continually
    * request records for a single partition. These `Stream`s will have to be
    * processed in parallel, using `parJoin` or `parJoinUnbounded`. Note that
    * when using `parJoin(n)` and `n` is smaller than the number of currently
    * assigned partitions, then there will be assigned partitions which won't
    * be processed. For that reason, prefer `parJoinUnbounded` and the actual
    * limit will be the number of assigned partitions.<br>
    * <br>
    * If you do not want to process all partitions in parallel, then you
    * can use [[stream]] instead, where records for all partitions are in
    * a single `Stream`.
    *
    * @note you have to first use `subscribe` to subscribe the consumer
    *       before using this `Stream`. If you forgot to subscribe, there
    *       will be a [[NotSubscribedException]] raised in the `Stream`.
    */
  def partitionedStream: Stream[F, Stream[F, CommittableMessage[F, K, V]]]

  /**
    * Overrides the fetch offsets that the consumer will use when reading the
    * next message. If this API is invoked for the same partition more than once,
    * the latest offset will be used. Note that you may lose data if this API is
    * arbitrarily used in the middle of consumption to reset the fetch offsets.
    */
  def seek(partition: TopicPartition, offset: Long): F[Unit]

  /**
    * Subscribes the consumer to the specified topics. Note that you have to
    * use one of the `subscribe` functions to subscribe to one or more topics
    * before using any of the provided `Stream`s, or a [[NotSubscribedException]]
    * will be raised in the `Stream`s.
    */
  def subscribeTo(firstTopic: String, remainingTopics: String*): F[Unit]

  /**
    * Subscribes the consumer to the specified topics. Note that you have to
    * use one of the `subscribe` functions to subscribe to one or more topics
    * before using any of the provided `Stream`s, or a [[NotSubscribedException]]
    * will be raised in the `Stream`s.
    *
    * @param topics the topics to which the consumer should subscribe
    */
  def subscribe[G[_]](topics: G[String])(implicit G: Reducible[G]): F[Unit]

  /**
    * Subscribes the consumer to the topics matching the specified `Regex`.
    * Note that you have to use one of the `subscribe` functions before you
    * can use any of the provided `Stream`s, or a [[NotSubscribedException]]
    * will be raised in the `Stream`s.
    *
    * @param regex the regex to which matching topics should be subscribed
    */
  def subscribe(regex: Regex): F[Unit]

  /**
    * Returns the first offset for the specified partitions.<br>
    * <br>
    * Timeout is determined by `default.api.timeout.ms`, which
    * is set using [[ConsumerSettings#withDefaultApiTimeout]].
    */
  def beginningOffsets(
    partitions: Set[TopicPartition]
  ): F[Map[TopicPartition, Long]]

  /**
    * Returns the first offset for the specified partitions.
    */
  def beginningOffsets(
    partitions: Set[TopicPartition],
    timeout: FiniteDuration
  ): F[Map[TopicPartition, Long]]

  /**
    * Returns the last offset for the specified partitions.<br>
    * <br>
    * Timeout is determined by `request.timeout.ms`, which
    * is set using [[ConsumerSettings#withRequestTimeout]].
    */
  def endOffsets(
    partitions: Set[TopicPartition]
  ): F[Map[TopicPartition, Long]]

  /**
    * Returns the last offset for the specified partitions.
    */
  def endOffsets(
    partitions: Set[TopicPartition],
    timeout: FiniteDuration
  ): F[Map[TopicPartition, Long]]

  /**
    * A `Fiber` that can be used to cancel the underlying consumer, or
    * wait for it to complete. If you're using [[stream]], or any other
    * provided stream in [[KafkaConsumer]], these will be automatically
    * interrupted when the underlying consumer has been cancelled or
    * when it finishes with an exception.<br>
    * <br>
    * Whenever `cancel` is invoked, an attempt will be made to stop the
    * underlying consumer. The `cancel` operation will not wait for the
    * consumer to shutdown. If you also want to wait for the shutdown
    * to complete, you can use `join`. Note that `join` is guaranteed
    * to complete after consumer shutdown, even when the consumer is
    * cancelled with `cancel`.<br>
    * <br>
    * This `Fiber` instance is usually only required if the consumer
    * needs to be cancelled due to some external condition, or when an
    * external process needs to be cancelled whenever the consumer has
    * shut down. Most of the time, when you're only using the streams
    * provided by [[KafkaConsumer]], there is no need to use this.
    */
  def fiber: Fiber[F, Unit]
}

private[kafka] object KafkaConsumer {
  private[this] def executionContextResource[F[_], K, V](
    settings: ConsumerSettings[K, V]
  )(
    implicit F: Sync[F]
  ): Resource[F, ExecutionContext] =
    settings.executionContext match {
      case Some(executionContext) => Resource.pure(executionContext)
      case None                   => consumerExecutionContextResource
    }

  private[this] def createConsumer[F[_], K, V](
    settings: ConsumerSettings[K, V],
    executionContext: ExecutionContext
  )(
    implicit F: Concurrent[F],
    context: ContextShift[F]
  ): Resource[F, Synchronized[F, Consumer[K, V]]] =
    Resource.make[F, Synchronized[F, Consumer[K, V]]] {
      settings.consumerFactory
        .create(settings)
        .flatMap(Synchronized[F].of)
    } { synchronized =>
      synchronized.use { consumer =>
        context.evalOn(executionContext) {
          F.delay(consumer.close(settings.closeTimeout.asJava))
        }
      }
    }

  private[this] def startConsumerActor[F[_], K, V](
    requests: Queue[F, Request[F, K, V]],
    polls: Queue[F, Request[F, K, V]],
    actor: KafkaConsumerActor[F, K, V]
  )(
    implicit F: Concurrent[F],
    context: ContextShift[F]
  ): Resource[F, Fiber[F, Unit]] =
    Resource.make {
      Deferred[F, Either[Throwable, Unit]].flatMap { deferred =>
        F.guaranteeCase {
            requests.tryDequeue1
              .flatMap(_.map(F.pure).getOrElse(polls.dequeue1))
              .flatMap(actor.handle(_) >> context.shift)
              .foreverM[Unit]
          } {
            case ExitCase.Error(e) => deferred.complete(Left(e))
            case _                 => deferred.complete(Right(()))
          }
          .start
          .map(fiber => Fiber[F, Unit](deferred.get.rethrow, fiber.cancel.start.void))
      }
    }(_.cancel)

  private[this] def startPollScheduler[F[_], K, V](
    polls: Queue[F, Request[F, K, V]],
    pollInterval: FiniteDuration
  )(
    implicit F: Concurrent[F],
    timer: Timer[F]
  ): Resource[F, Fiber[F, Unit]] =
    Resource.make {
      Deferred[F, Either[Throwable, Unit]].flatMap { deferred =>
        F.guaranteeCase {
            polls
              .enqueue1(Request.poll)
              .flatMap(_ => timer.sleep(pollInterval))
              .foreverM[Unit]
          } {
            case ExitCase.Error(e) => deferred.complete(Left(e))
            case _                 => deferred.complete(Right(()))
          }
          .start
          .map(fiber => Fiber[F, Unit](deferred.get.rethrow, fiber.cancel.start.void))
      }
    }(_.cancel)

  private[this] def createKafkaConsumer[F[_], K, V](
    requests: Queue[F, Request[F, K, V]],
    actor: Fiber[F, Unit],
    polls: Fiber[F, Unit]
  )(implicit F: Concurrent[F]): KafkaConsumer[F, K, V] =
    new KafkaConsumer[F, K, V] {
      override val fiber: Fiber[F, Unit] = {
        val actorFiber =
          Fiber[F, Unit](F.guaranteeCase(actor.join) {
            case ExitCase.Completed => polls.cancel
            case _                  => F.unit
          }, actor.cancel)

        val pollsFiber =
          Fiber[F, Unit](F.guaranteeCase(polls.join) {
            case ExitCase.Completed => actor.cancel
            case _                  => F.unit
          }, polls.cancel)

        actorFiber combine pollsFiber
      }

      override def partitionedStream: Stream[F, Stream[F, CommittableMessage[F, K, V]]] = {
        val chunkQueue: F[Queue[F, Option[Chunk[CommittableMessage[F, K, V]]]]] =
          Queue.bounded[F, Option[Chunk[CommittableMessage[F, K, V]]]](1)

        type PartitionRequest =
          (Chunk[CommittableMessage[F, K, V]], FetchCompletedReason)

        def enqueueStream(
          partition: TopicPartition,
          partitions: Queue[F, Stream[F, CommittableMessage[F, K, V]]]
        ): F[Unit] =
          chunkQueue.flatMap { chunks =>
            Deferred[F, Unit].flatMap { dequeueDone =>
              Deferred[F, Unit].flatMap { partitionRevoked =>
                val shutdown = F.race(fiber.join.attempt, dequeueDone.get).void
                partitions.enqueue1 {
                  Stream.eval {
                    F.guarantee {
                        Stream
                          .repeatEval {
                            Deferred[F, PartitionRequest].flatMap { deferred =>
                              val request = Request.Fetch(partition, deferred)
                              val fetch = requests.enqueue1(request) >> deferred.get
                              F.race(shutdown, fetch).flatMap {
                                case Left(()) => F.unit
                                case Right((chunk, reason)) =>
                                  val enqueueChunk =
                                    if (chunk.nonEmpty)
                                      chunks.enqueue1(Some(chunk))
                                    else F.unit

                                  val completeRevoked =
                                    if (reason.topicPartitionRevoked)
                                      partitionRevoked.complete(())
                                    else F.unit

                                  enqueueChunk >> completeRevoked
                              }
                            }
                          }
                          .interruptWhen(F.race(shutdown, partitionRevoked.get).void.attempt)
                          .compile
                          .drain
                      }(F.race(dequeueDone.get, chunks.enqueue1(None)).void)
                      .start
                      .as {
                        chunks.dequeue.unNoneTerminate
                          .flatMap(Stream.chunk)
                          .covary[F]
                          .onFinalize(dequeueDone.complete(()))
                      }
                  }.flatten
                }
              }
            }
          }

        def enqueueStreams(
          assigned: NonEmptySet[TopicPartition],
          partitions: Queue[F, Stream[F, CommittableMessage[F, K, V]]]
        ): F[Unit] = assigned.foldLeft(F.unit)(_ >> enqueueStream(_, partitions))

        def onRebalance(
          partitions: Queue[F, Stream[F, CommittableMessage[F, K, V]]]
        ): OnRebalance[F, K, V] = OnRebalance(
          onAssigned = assigned => enqueueStreams(assigned.partitions, partitions),
          onRevoked = _ => F.unit
        )

        def requestAssignment(
          partitions: Queue[F, Stream[F, CommittableMessage[F, K, V]]]
        ): F[SortedSet[TopicPartition]] = {
          Deferred[F, Either[Throwable, SortedSet[TopicPartition]]].flatMap { deferred =>
            val request = Request.Assignment[F, K, V](deferred, Some(onRebalance(partitions)))
            val assignment = requests.enqueue1(request) >> deferred.get.rethrow
            F.race(fiber.join.attempt, assignment).map {
              case Left(_)         => SortedSet.empty[TopicPartition]
              case Right(assigned) => assigned
            }
          }
        }

        def initialEnqueue(partitions: Queue[F, Stream[F, CommittableMessage[F, K, V]]]): F[Unit] =
          requestAssignment(partitions).flatMap { assigned =>
            if (assigned.nonEmpty) {
              val nonEmpty = NonEmptySet.fromSetUnsafe(assigned)
              enqueueStreams(nonEmpty, partitions)
            } else F.unit
          }

        val partitionQueue: F[Queue[F, Stream[F, CommittableMessage[F, K, V]]]] =
          Queue.unbounded[F, Stream[F, CommittableMessage[F, K, V]]]

        Stream.eval(partitionQueue).flatMap { partitions =>
          Stream.eval(initialEnqueue(partitions)) >>
            partitions.dequeue.interruptWhen(fiber.join.attempt)
        }
      }

      override def stream: Stream[F, CommittableMessage[F, K, V]] = {
        val requestAssignment: F[SortedSet[TopicPartition]] =
          Deferred[F, Either[Throwable, SortedSet[TopicPartition]]].flatMap { deferred =>
            val request = Request.Assignment[F, K, V](deferred, onRebalance = None)
            val assignment = requests.enqueue1(request) >> deferred.get.rethrow
            F.race(fiber.join.attempt, assignment).map {
              case Left(_)         => SortedSet.empty[TopicPartition]
              case Right(assigned) => assigned
            }
          }

        type PartitionRequest =
          (Chunk[CommittableMessage[F, K, V]], ExpiringFetchCompletedReason)

        def chunkQueue(size: Int): F[Queue[F, Option[Chunk[CommittableMessage[F, K, V]]]]] =
          Queue.bounded[F, Option[Chunk[CommittableMessage[F, K, V]]]](size)

        def requestPartitions(
          assigned: SortedSet[TopicPartition]
        ): F[Stream[F, CommittableMessage[F, K, V]]] =
          chunkQueue(assigned.size).flatMap { chunks =>
            Deferred[F, Unit].flatMap { dequeueDone =>
              F.guarantee {
                  assigned.toList
                    .traverse { partition =>
                      Deferred[F, PartitionRequest].flatMap { deferred =>
                        val request = Request.ExpiringFetch(partition, deferred)
                        val fetch = requests.enqueue1(request) >> deferred.get
                        F.race(F.race(fiber.join.attempt, dequeueDone.get), fetch).flatMap {
                          case Right((chunk, _)) if chunk.nonEmpty =>
                            chunks.enqueue1(Some(chunk))
                          case _ => F.unit
                        }
                      }.start
                    }
                    .flatMap(_.combineAll.join)
                }(F.race(dequeueDone.get, chunks.enqueue1(None)).void)
                .start
                .as {
                  chunks.dequeue.unNoneTerminate
                    .flatMap(Stream.chunk)
                    .covary[F]
                    .onFinalize(dequeueDone.complete(()))
                }
            }
          }

        Stream
          .repeatEval(requestAssignment)
          .filter(_.nonEmpty)
          .evalMap(requestPartitions)
          .flatten
          .interruptWhen(fiber.join.attempt)
      }

      private[this] def request[A](
        request: Deferred[F, Either[Throwable, A]] => Request[F, K, V]
      ): F[A] =
        Deferred[F, Either[Throwable, A]].flatMap { deferred =>
          requests.enqueue1(request(deferred)) >>
            F.race(fiber.join, deferred.get.rethrow).flatMap {
              case Left(()) => F.raiseError(ConsumerShutdownException())
              case Right(a) => F.pure(a)
            }
        }

      override def seek(partition: TopicPartition, offset: Long): F[Unit] =
        request { deferred =>
          Request.Seek(
            partition = partition,
            offset = offset,
            deferred = deferred
          )
        }

      override def subscribeTo(firstTopic: String, remainingTopics: String*): F[Unit] =
        subscribe(NonEmptyList.of(firstTopic, remainingTopics: _*))

      override def subscribe[G[_]](topics: G[String])(implicit G: Reducible[G]): F[Unit] =
        request { deferred =>
          Request.SubscribeTopics(
            topics = topics.toNonEmptyList,
            deferred = deferred
          )
        }

      override def subscribe(regex: Regex): F[Unit] =
        request { deferred =>
          Request.SubscribePattern(
            pattern = regex.pattern,
            deferred = deferred
          )
        }

      override def beginningOffsets(
        partitions: Set[TopicPartition]
      ): F[Map[TopicPartition, Long]] =
        request { deferred =>
          Request.BeginningOffsets(
            partitions = partitions,
            timeout = None,
            deferred = deferred
          )
        }

      override def beginningOffsets(
        partitions: Set[TopicPartition],
        timeout: FiniteDuration
      ): F[Map[TopicPartition, Long]] =
        request { deferred =>
          Request.BeginningOffsets(
            partitions = partitions,
            timeout = Some(timeout),
            deferred = deferred
          )
        }

      override def endOffsets(
        partitions: Set[TopicPartition]
      ): F[Map[TopicPartition, Long]] =
        request { deferred =>
          Request.EndOffsets(
            partitions = partitions,
            timeout = None,
            deferred = deferred
          )
        }

      override def endOffsets(
        partitions: Set[TopicPartition],
        timeout: FiniteDuration
      ): F[Map[TopicPartition, Long]] =
        request { deferred =>
          Request.EndOffsets(
            partitions = partitions,
            timeout = Some(timeout),
            deferred = deferred
          )
        }

      override def toString: String =
        "KafkaConsumer$" + System.identityHashCode(this)
    }

  def consumerResource[F[_], K, V](
    settings: ConsumerSettings[K, V]
  )(
    implicit F: ConcurrentEffect[F],
    context: ContextShift[F],
    timer: Timer[F]
  ): Resource[F, KafkaConsumer[F, K, V]] =
    Resource.liftF(Queue.unbounded[F, Request[F, K, V]]).flatMap { requests =>
      Resource.liftF(Queue.bounded[F, Request[F, K, V]](1)).flatMap { polls =>
        Resource.liftF(Ref.of[F, State[F, K, V]](State.empty)).flatMap { ref =>
          Resource.liftF(Jitter.default[F]).flatMap { implicit jitter =>
            executionContextResource(settings).flatMap { executionContext =>
              createConsumer(settings, executionContext).flatMap { synchronized =>
                val actor =
                  new KafkaConsumerActor(
                    settings = settings,
                    executionContext = executionContext,
                    ref = ref,
                    requests = requests,
                    synchronized = synchronized
                  )

                startConsumerActor(requests, polls, actor).flatMap { actor =>
                  startPollScheduler(polls, settings.pollInterval).map { polls =>
                    createKafkaConsumer(requests, actor, polls)
                  }
                }
              }
            }
          }
        }
      }
    }
}
