package scalabythebay.cofree

import scalaz._
import Scalaz._
import scalaz.concurrent.Task

package object fibs {
  def fibs[F[_]: Monad]: Cofree[F, Int] = {
    def unfold(prev1: Int, prev2: Int): Cofree[F, Int] = {
      val sum = prev1 + prev2

      Cofree(sum, unfold(prev2, sum).point[F])
    }

    unfold(0, 1)
  }

  val fibsNeed: Cofree[Need, Int] = fibs[Need]

  val fibsTask: Cofree[Task, Int] = fibs[Task]
}

package object streams {
  def append[F[_]: ApplicativePlus, A](c1: Cofree[F, A], c2: Cofree[F, A]): Cofree[F, A] =
    Cofree(c1.head, c1.tail.map(t => append(t, c2)) <+> c2.point[F])

  def zipWith[F[_]: Zip: Functor, A, B, C](c1: Cofree[F, A], c2: Cofree[F, B])(f: (A, B) => C): Cofree[F, C] =
    Cofree(f(c1.head, c2.head), Zip[F].zipWith(c1.tail, c2.tail)(zipWith(_, _)(f)))

  def scan[F[_]: Functor, A, S](c: Cofree[F, A])(s: S)(f: (S, A) => S): Cofree[F, S] = {
    val s2 = f(s, c.head)

    Cofree(s2, c.tail.map(scan(_)(s2)(f)))
  }

  def zeros[F[_]: Functor, A: Monoid](c: Cofree[F, A])(p: A => Boolean): Cofree[F, A] =
    if (p(c.head)) Cofree(c.head, c.tail.map(zeros(_)(p)))
    else Cofree(mzero[A], c.tail.map(zeros(_)(p)))

  def filter[F[_]: Monad, A](c: Cofree[F, A])(p: A => Boolean): F[Cofree[F, A]] =
    if (p(c.head)) Cofree(c.head, c.tail.flatMap(filter(_)(p))).point[F]
    else c.tail.flatMap(filter(_)(p))

  def collect[F[_]: MonadPlus, A](c: Cofree[F, A]): F[Vector[A]] = {
    val singleton = Vector[A](c.head).point[F]

    (singleton |@| c.tail.flatMap(collect(_)))(_ ++ _) <+> singleton
  }

  def disperse[F[_]: ApplicativePlus, A](seq: Seq[A]): Option[Cofree[F, A]] =
    seq.foldRight[Option[Cofree[F, A]]](None) {
      case (a, None) => Some(Cofree(a, mempty[F, Cofree[F, A]]))
      case (a, Some(tail)) => Some(Cofree(a, tail.point[F]))
    }

  object examples {
    val tenInts = disperse[Option, Int](1 :: 2 :: 3 :: 4 :: 5 :: 6 :: 7 :: 8 :: 9 :: 10 :: Nil).get

    val tenTwice = append(tenInts, tenInts)

    val zipped = zipWith(tenInts, tenInts)((_, _))
  }
}

package object pipes {
  type Pipe[F[_], A, B] = Cofree[Kleisli[F, A, ?], B]

  type Source[F[_], A] = Pipe[F, Unit, A]

  type Sink[F[_], A] = Pipe[F, A, Unit]

  def pipe[F[_]: Applicative, A, B, C](from: Pipe[F, A, B], to: Pipe[F, B, C]): Pipe[F, A, C] =
    Cofree[Kleisli[F, A, ?], C](
      to.head,
      Kleisli(a => (from.tail.run(a) |@| to.tail.run(from.head))(pipe(_, _))))

  // e.g. Unit
  def runPipe[F[_]: Monad, A: Monoid](pipe: Pipe[F, A, A]): F[A] =
    (pipe.head.point[F] |@| pipe.tail.run(mzero[A]).flatMap(runPipe(_)))(_ |+| _)
}

package object io_pipes {
  import pipes._

  type IOPipe[A, B] = Pipe[Task, A, B]

  def IOPipe[A, B](head: B, tail: Kleisli[Task, A, IOPipe[A, B]]): IOPipe[A, B] =
    Cofree[Kleisli[Task, A, ?], B](head, tail)

  type IOSource[A] = Source[Task, A]

  type IOSink[A] = Sink[Task, A]

  object examples {
    val consoleSource: Task[IOSource[String]] =
      Task.delay(readLine()).map(IOPipe(_, Kleisli(_ => consoleSource)))

    val consoleSink: IOSink[String] =
      IOPipe((), Kleisli(line => Task.delay(println(line)).map(_ => consoleSink)))

    val echoProgram: Task[Unit] = consoleSource.flatMap(source => runPipe(pipe(source, consoleSink)))
  }
}

package object bytestream {
  import io_pipes._

  type BytePipe = IOPipe[Array[Byte], Array[Byte]]

  type ByteSource = IOSource[Array[Byte]]

  type ByteSink = IOSink[Array[Byte]]
}

package object merge {
  import pipes._

  type Select[F[_], A] = Coproduct[F, F, A]

  type Merge[F[_], A, B] = Pipe[Select[F, ?], A, B]

  def merge[F[_]: Applicative, A, B](left: Source[F, A], right: Source[F, A])(s: Select[Id, Merge[F, A, B]]): Source[F, B] = {
    def embed[F[_]: Functor, A](s: Select[F, A]): F[Select[Id, A]] = s.run match {
      case -\/ (fa) => fa.map(a => Coproduct[Id, Id, A](a.left [A]))
      case  \/-(fa) => fa.map(a => Coproduct[Id, Id, A](a.right[A]))
    }

    def step(y: Merge[F, A, B], fs: F[Source[F, B]]): Source[F, B] =
      Cofree[Kleisli[F, Unit, ?], B](y.head, Kleisli(_ => fs))

    s.run match {
      case -\/ (y) => step(y, (left.tail.run (()) |@| embed(y.tail.run( left.head)))((left,  y) => merge(left, right)(y)))
      case  \/-(y) => step(y, (right.tail.run(()) |@| embed(y.tail.run(right.head)))((right, y) => merge(left, right)(y)))
    }
  }

  def mergeLeft[F[_]: Applicative, A, B](left: Source[F, A], right: Source[F, A])(y: Merge[F, A, B]): Source[F, B] =
    merge(left, right)(Coproduct[Id, Id, Merge[F, A, B]](y.left))

  def mergeRight[F[_]: Applicative, A, B](left: Source[F, A], right: Source[F, A])(y: Merge[F, A, B]): Source[F, B] =
    merge(left, right)(Coproduct[Id, Id, Merge[F, A, B]](y.right))
}

package object fork {
  import pipes._

  def fork[F[_]: Applicative, A](left: Sink[F, A], right: Sink[F, A]): Sink[F, A] =
    Cofree[Kleisli[F, A, ?], Unit]((),
      Kleisli(a => (left.tail.run(a) |@| right.tail.run(a))(fork(_, _))))
}

package object machine {
  // Strangely missing from Scalaz:
  final case class Product[F[_], G[_], A](run: (F[A], G[A]))

  def productWith[F[_]: Functor, G[_]: Functor, A, B, C](c1: Cofree[F, A], c2: Cofree[G, B])(f: (A, B) => C): Cofree[Product[F, G, ?], C] =
    Cofree[Product[F, G, ?], C](f(c1.head, c2.head),
      Product((c1.tail.map(productWith(_, c2)(f)), c2.tail.map(productWith(c1, _)(f)))))
}
