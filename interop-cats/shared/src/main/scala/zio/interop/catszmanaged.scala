/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
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

package zio
package interop

import cats.arrow.FunctionK
import cats.effect.Resource.{ Allocate, Bind, Suspend }
import cats.effect.{ Async, Effect, LiftIO, Resource, Sync, IO => CIO }
import cats.{ Applicative, Bifunctor, Monad, MonadError, Monoid, Semigroup, SemigroupK }

trait CatsZManagedSyntax {
  import scala.language.implicitConversions

  implicit final def catsIOResourceSyntax[F[_], A](resource: Resource[F, A]): CatsIOResourceSyntax[F, A] =
    new CatsIOResourceSyntax(resource)

  implicit final def zioResourceSyntax[R, E <: Throwable, A](r: Resource[ZIO[R, E, ?], A]): ZIOResourceSyntax[R, E, A] =
    new ZIOResourceSyntax(r)

  implicit final def zManagedSyntax[R, E, A](managed: ZManaged[R, E, A]): ZManagedSyntax[R, E, A] =
    new ZManagedSyntax(managed)

}

final class ZIOResourceSyntax[R, E <: Throwable, A](private val resource: Resource[ZIO[R, E, ?], A]) extends AnyVal {

  /**
   * Convert a cats Resource into a ZManaged.
   * Beware that unhandled error during release of the resource will result in the fiber dying.
   */
  def toManagedZIO: ZManaged[R, E, A] = {
    def go[A1](res: Resource[ZIO[R, E, ?], A1]): ZManaged[R, E, A1] =
      res match {
        case Allocate(resource) =>
          ZManaged(resource.map { case (a, r) => Reservation(ZIO.succeed(a), e => r(exitToExitCase(e)).orDie) })
        case Bind(source, fs) =>
          go(source).flatMap(s => go(fs(s)))
        case Suspend(resource) =>
          ZManaged.unwrap(resource.map(go))
      }

    go(resource)
  }
}

final class CatsIOResourceSyntax[F[_], A](private val resource: Resource[F, A]) extends AnyVal {

  /**
   * Convert a cats Resource into a ZManaged.
   * Beware that unhandled error during release of the resource will result in the fiber dying.
   */
  def toManaged[R](implicit L: LiftIO[ZIO[R, Throwable, ?]], F: Effect[F]): ZManaged[R, Throwable, A] = {
    def go[A1](resource: Resource[CIO, A1]): ZManaged[R, Throwable, A1] =
      resource match {
        case Allocate(res) =>
          ZManaged(L.liftIO(res.map {
            case (a, r) => Reservation(ZIO.succeed(a), e => L.liftIO(r(exitToExitCase(e))).orDie)
          }))
        case Bind(source, fs) =>
          go(source).flatMap(s => go(fs(s)))
        case Suspend(res) =>
          ZManaged.unwrap(L.liftIO(res).map(go))
      }

    go(resource.mapK(FunctionK.lift(F.toIO)))
  }
}

final class ZManagedSyntax[R, E, A](private val managed: ZManaged[R, E, A]) extends AnyVal {

  def toResourceZIO(implicit ev: Applicative[ZIO[R, E, ?]]): Resource[ZIO[R, E, ?], A] =
    Resource
      .makeCase(managed.reserve)((r, e) => r.release(exitCaseToExit(e)).unit)
      .evalMap(_.acquire)

  def toResource[F[_]](implicit F: Async[F], ev: Effect[ZIO[R, E, ?]]): Resource[F, A] =
    toResourceZIO.mapK(Lambda[FunctionK[ZIO[R, E, ?], F]](F liftIO ev.toIO(_)))

}

trait CatsEffectZManagedInstances {

  implicit def liftIOZManagedInstances[R](
    implicit ev: LiftIO[ZIO[R, Throwable, ?]]
  ): LiftIO[ZManaged[R, Throwable, ?]] =
    new LiftIO[ZManaged[R, Throwable, ?]] {
      override def liftIO[A](ioa: CIO[A]): ZManaged[R, Throwable, A] =
        ZManaged.fromEffect(ev.liftIO(ioa))
    }

  implicit def syncZManagedInstances[R]: Sync[ZManaged[R, Throwable, ?]] =
    new CatsZManagedSync[R]

}

trait CatsZManagedInstances extends CatsZManagedInstances1 {

  implicit def monadErrorZManagedInstances[R, E]: MonadError[ZManaged[R, E, ?], E] =
    new CatsZManagedMonadError

  implicit def monoidZManagedInstances[R, E, A](implicit ev: Monoid[A]): Monoid[ZManaged[R, E, A]] =
    new Monoid[ZManaged[R, E, A]] {
      override def empty: ZManaged[R, E, A] = ZManaged.succeed(ev.empty)

      override def combine(x: ZManaged[R, E, A], y: ZManaged[R, E, A]): ZManaged[R, E, A] = x.zipWith(y)(ev.combine)
    }

}

sealed trait CatsZManagedInstances1 {

  implicit def monadZManagedInstances[R, E]: Monad[ZManaged[R, E, ?]] = new CatsZManagedMonad

  implicit def semigroupZManagedInstances[R, E, A](implicit ev: Semigroup[A]): Semigroup[ZManaged[R, E, A]] =
    (x: ZManaged[R, E, A], y: ZManaged[R, E, A]) => x.zipWith(y)(ev.combine)

  implicit def semigroupKZManagedInstances[R, E]: SemigroupK[ZManaged[R, E, ?]] = new CatsZManagedSemigroupK

  implicit def bifunctorZManagedInstances[R]: Bifunctor[ZManaged[R, ?, ?]] = new Bifunctor[ZManaged[R, ?, ?]] {
    override def bimap[A, B, C, D](fab: ZManaged[R, A, B])(f: A => C, g: B => D): ZManaged[R, C, D] =
      fab.mapError(f).map(g)
  }

}

private class CatsZManagedMonad[R, E] extends Monad[ZManaged[R, E, ?]] {
  override def pure[A](x: A): ZManaged[R, E, A] = ZManaged.succeed(x)

  override def flatMap[A, B](fa: ZManaged[R, E, A])(f: A => ZManaged[R, E, B]): ZManaged[R, E, B] = fa.flatMap(f)

  override def tailRecM[A, B](a: A)(f: A => ZManaged[R, E, Either[A, B]]): ZManaged[R, E, B] =
    ZManaged.suspend(f(a)).flatMap {
      case Left(nextA) => tailRecM(nextA)(f)
      case Right(b)    => ZManaged.succeed(b)
    }
}

private class CatsZManagedMonadError[R, E] extends CatsZManagedMonad[R, E] with MonadError[ZManaged[R, E, ?], E] {
  override def raiseError[A](e: E): ZManaged[R, E, A] = ZManaged.fromEffect(ZIO.fail(e))

  override def handleErrorWith[A](fa: ZManaged[R, E, A])(f: E => ZManaged[R, E, A]): ZManaged[R, E, A] =
    fa.catchAll(f)
}

/**
 * lossy, throws away errors using the "first success" interpretation of SemigroupK
 */
private class CatsZManagedSemigroupK[R, E] extends SemigroupK[ZManaged[R, E, ?]] {
  override def combineK[A](x: ZManaged[R, E, A], y: ZManaged[R, E, A]): ZManaged[R, E, A] =
    x.orElse(y)
}

private class CatsZManagedSync[R] extends CatsZManagedMonadError[R, Throwable] with Sync[ZManaged[R, Throwable, ?]] {

  override final def delay[A](thunk: => A): ZManaged[R, Throwable, A] = ZManaged.fromEffect(ZIO.effect(thunk))

  override final def suspend[A](thunk: => zio.ZManaged[R, Throwable, A]): zio.ZManaged[R, Throwable, A] =
    ZManaged.unwrap(ZIO.effect(thunk))

  override def bracketCase[A, B](
    acquire: zio.ZManaged[R, Throwable, A]
  )(
    use: A => zio.ZManaged[R, Throwable, B]
  )(
    release: (A, cats.effect.ExitCase[Throwable]) => zio.ZManaged[R, Throwable, Unit]
  ): zio.ZManaged[R, Throwable, B] =
    ZManaged {
      Ref.make[List[Exit[_, _] => URIO[R, _]]](Nil).map { finalizers =>
        Reservation(
          ZIO.uninterruptibleMask { restore =>
            for {
              a <- for {
                    resA <- acquire.reserve
                    _    <- finalizers.update(resA.release :: _)
                    a    <- restore(resA.acquire)
                  } yield a
              exitB <- (for {
                        resB <- restore(use(a).reserve.uninterruptible)
                        _    <- finalizers.update(resB.release :: _)
                        b    <- restore(resB.acquire)
                      } yield b).run
              _ <- for {
                    resC <- release(a, exitToExitCase(exitB)).reserve
                    _    <- finalizers.update(resC.release :: _)
                    _    <- restore(resC.acquire)
                  } yield ()
              b <- ZIO.done(exitB)
            } yield b
          },
          exitU =>
            for {
              fs    <- finalizers.get
              exits <- ZIO.foreach(fs)(_(exitU).run)
              _     <- ZIO.done(Exit.collectAll(exits).getOrElse(Exit.unit))
            } yield ()
        )
      }
    }
}
