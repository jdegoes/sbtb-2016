autoscale: true

# Streams for (Co)Free!
### John A. De Goes — @jdegoes

---

# Agenda

 * Induction/Coinduction
 * Universality of Streams
 * Cofree: A Principled Stream
 * Challenge

---

# Induction

### Statefully tear down a structure to culminate in a value.

---

# Inductive Programs
### Examples

* Parse a config file
* Sort a list 
* Send an HTTP response
* Compute the millionth digit of pi

---

# Surprise! 
## *Most(?) Business Problems Are Coinductive*

---

# Coinduction

### Start from a value to statefully build up an infinite structure.

---

# Coinductive Processes
### Examples

 * Produce the next state of the UI given a user-input
 * Produce current state of config given update to config
 * Produce the next sort of a list given a new element
 * Transform stream of requests to responses
 * Produce (all) the digits of pi

---

## Programming Languages Hate Coinduction

### But "Reactive" and "Streams"

---

# By Any Other Name

### A *stream* is a mainstream example of a coinductive process.

 * Data processing
 * Web servers
 * User-interfaces
 * So much more...

---

# Streams

 ✓ Stateful

 ✓ Incremental computation

 ✓ Non-termination

 ✓ Consume & Emit

---

# Streams
## Choices, Choices

 * Akka Streams
 * Java Streams
 * Scalaz-Streams
 * FS<sup>2</sup>

---

## John's Laws of Clean Functional Code

1. *Reasonability* is directly proportional to *totality* & *referential-transparency*.
2. *Composability* is inversely proportional to *number of types*.
3. *Obfuscation* is directly proportional to *number of lawless interfaces*.
4. *Correctness* is directly proportional to *degree of polymorphism*.
5. *Shoddiness* is directly proportional to *encapsulation*.
6. *Volume* is inversely proportional to *orthogonality*.

---

# Akka Stream
## akka.stream

AbruptTerminationException AbstractShape ActorAttributes ActorMaterializer ActorMaterializerSettings AmorphousShape Attributes BidiShape BindFailedException BufferOverflowException Client ClosedShape ConnectionException DelayOverflowStrategy EagerClose FanInShape FanInShape10 FanInShape11 FanInShape12 FanInShape13 FanInShape14 FanInShape15 FanInShape16 FanInShape17 FanInShape18 FanInShape19 FanInShape1N FanInShape2 FanInShape20 FanInShape21 FanInShape22 FanInShape3 FanInShape4 FanInShape5 FanInShape6 FanInShape7 FanInShape8 FanInShape9 FanOutShape FanOutShape10 FanOutShape11 FanOutShape12 FanOutShape13 FanOutShape14 FanOutShape15 FanOutShape16 FanOutShape17 FanOutShape18 FanOutShape19 FanOutShape2 FanOutShape20 FanOutShape21 FanOutShape22 FanOutShape3 FanOutShape4 FanOutShape5 FanOutShape6 FanOutShape7 FanOutShape8 FanOutShape9 FlowMonitor FlowMonitorState FlowShape Fusing Graph IgnoreBoth IgnoreCancel IgnoreComplete Inlet InPort IOResult KillSwitch KillSwitches MaterializationContext MaterializationException Materializer MaterializerLoggingProvider Outlet OutPort OverflowStrategy QueueOfferResult RateExceededException Server Shape SharedKillSwitch SinkShape SourceShape StreamLimitReachedException StreamSubscriptionTimeoutSettings StreamSubscriptionTimeoutTerminationMode StreamTcpException SubstreamCancelStrategy Supervision ThrottleMode TLSClientAuth TLSClosing TLSProtocol TLSRole UniformFanInShape UniformFanOutShape UniqueKillSwitch

---

# Cofree
### ALL THE POWER OF FREE, NOW WITH STREAMING!!!

---

# Cofree
### Huh?

```scala
final case class Cofree[F[_], A](head: A, tail: F[Cofree[F, A]])
```

---

# Cofree
### Cofree[F, A] is a coinductive process that generates `A`'s using effect `F`.

---

# Cofree
### Cofree[F, A] is a current position `A` on a landscape that requires effect `F` to move to a new position.

---

# Cofree
### Comonad Methods

 * Where am I? `def extract(f: Cofree[F, A]): A`
 * Terraform! `def extend(f: Cofree[F, A] => B): Cofree[F, B]`

---

# Cofree
### Fibs

```scala
final case class Cofree[F[_], A](head: A, tail: F[Cofree[F, A]])

// final case class Name[A](() => A)
val fibs: Cofree[Name, Int] = {
  def unfold(prev1: Int, prev2: Int): Cofree[Name, Int] =
    Cofree(prev1 + prev2, Name(unfold(prev2, prev1 + prev2)))

  unfold(0, 1)
}
```

---

# Cofree
### Append

```scala
def append[F[_]: ApplicativePlus, A](c1: Cofree[F, A], c2: Cofree[F, A]): Cofree[F, A] =
  Cofree(c1.head, c1.tail.map(t => append(t, c2)) <+> c2.point[F])
```

---

# Cofree
### Collect/Disperse

```scala
def collect[F[_]: MonadPlus, A](c: Cofree[F, A]): F[Vector[A]] = {
  val singleton = Vector[A](c.head).point[F]

  (singleton |@| c.tail.flatMap(collect(_)))(_ ++ _) <+> singleton
}

def disperse[F[_]: ApplicativePlus, A](seq: Seq[A]): Option[Cofree[F, A]] =
  seq.foldRight[Option[Cofree[F, A]]](None) {
    case (a, None) => Some(Cofree(a, mempty[F, Cofree[F, A]]))
    case (a, Some(tail)) => Some(Cofree(a, tail.point[F]))
  }
```

---

# Cofree
### Zip

```scala
def zipWith[F[_]: Zip: Functor, A, B, C](c1: Cofree[F, A], c2: Cofree[F, B])(f: (A, B) => C): Cofree[F, C] =
  Cofree(f(c1.head, c2.head), Zip[F].zipWith(c1.tail, c2.tail)(zipWith(_, _)(f)))
```

---

# Cofree
### Scan

```scala
def scan[F[_]: Functor, A, S](c: Cofree[F, A])(s: S)(f: (S, A) => S): Cofree[F, S] = {
  val s2 = f(s, c.head)

  Cofree(s2, c.tail.map(scan(_)(s2)(f)))
}
```

---


# Cofree
### Filter

```scala
def zeros[F[_]: Functor, A: Monoid](c: Cofree[F, A])(p: A => Boolean): Cofree[F, A] =
  if (p(c.head)) Cofree(c.head, c.tail.map(zeros(_)(p)))
  else Cofree(mzero[A], c.tail.map(zeros(_)(p)))
def filter[F[_]: Monad, A](c: Cofree[F, A])(p: A => Boolean): F[Cofree[F, A]] =
  if (p(c.head)) Cofree(c.head, c.tail.flatMap(filter(_)(p))).point[F]
  else c.tail.flatMap(filter(_)(p))
```

---

# Cofree
### Pipes: Types

```scala
// final case class Kleisli[F[_], A, B](run: A => F[B])

type Pipe[F[_], A, B] = Cofree[Kleisli[F, A, ?], B]

type Source[F[_], A] = Pipe[F, Unit, A]

type Sink[F[_], A] = Pipe[F, A, Unit]
```

---

# Cofree
### Pipes: Utilities

```scala
def pipe[F[_]: Applicative, A, B, C](from: Pipe[F, A, B], to: Pipe[F, B, C]): Pipe[F, A, C] =
  Cofree[Kleisli[F, A, ?], C](
    to.head,
    Kleisli(a => (from.tail.run(a) |@| to.tail.run(from.head))(pipe(_, _))))

// e.g. Unit
def runPipe[F[_]: Monad, A: Monoid](pipe: Pipe[F, A, A]): F[A] =
  (pipe.head.point[F] |@| pipe.tail.run(mzero[A]).flatMap(runPipe(_)))(_ |+| _)
```

---

# Cofree
### IO, Byte Streams, Etc.

```scala
type IOPipe[A, B] = Pipe[Task, A, B]

...

type BytePipe = IOPipe[Array[Byte], Array[Byte]]
```

---

# Cofree
### Merging: Types

```scala
type Select[F[_], A] = Coproduct[F, F, A]

type Merge[F[_], A, B] = Pipe[Select[F, ?], A, B]
```

---

# Cofree
### Merging: Function

```scala
def merge[F[_]: Applicative, A, B](left: Source[F, A], right: Source[F, A])
	(s: Select[Id, Merge[F, A, B]]): Source[F, B] = {
  def embed[F[_]: Functor, A](s: Select[F, A]): F[Select[Id, A]] = s.run match {
    case -\/ (fa) => fa.map(a => Coproduct[Id, Id, A](a.left [A]))
    case  \/-(fa) => fa.map(a => Coproduct[Id, Id, A](a.right[A]))
  }

  def step(y: Merge[F, A, B], fs: F[Source[F, B]]): Source[F, B] =
    Cofree[Kleisli[F, Unit, ?], B](y.head, Kleisli(_ => fs))

  s.run match {
    case -\/ (y) => 
      step(y, 
        (left.tail.run(()) |@| embed(y.tail.run(left.head)))(
          (left,  y) => merge(left, right)(y)))
    case  \/-(y) => 
      step(y, 
        (right.tail.run(()) |@| embed(y.tail.run(right.head)))(
          (right, y) => merge(left, right)(y)))
  }
}
```

---

# Cofree
### Forking

```scala
def fork[F[_]: Applicative, A](left: Sink[F, A], right: Sink[F, A]): Sink[F, A] =
  Cofree[Kleisli[F, A, ?], Unit]((),
    Kleisli(a => (left.tail.run(a) |@| right.tail.run(a))(fork(_, _))))
```

---

# Cofree
### Machines

```scala
case class Instr[F[_], A](
  goLeft:  F[A],
  goRight: F[A],
  goUp:    F[A],
  goDown:  F[A])

// Cofree[Instr[F, ?], A]

def productWith[F[_]: Functor, G[_]: Functor, A, B, C](
    c1: Cofree[F, A], c2: Cofree[G, B])(
      f: (A, B) => C): Cofree[Product[F, G, ?], C] =
  Cofree[Product[F, G, ?], C](f(c1.head, c2.head),
    Product((c1.tail.map(productWith(_, c2)(f)), 
      c2.tail.map(productWith(c1, _)(f)))))
```

---

# Cofree
### Production-Ready?

# No, BUT...

---

# Challenge
### Go Do Something That's A Tiny Bit Simpler...A Bit More Functional...

---

# THANK YOU
### Follow me on Twitter at @jdegoes
