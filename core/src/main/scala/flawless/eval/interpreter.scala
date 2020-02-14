package flawless.eval

import cats.implicits._
import flawless.data.Test
import flawless.data.Assertion
import flawless.data.TestRun
import cats.tagless.finalAlg
import cats.effect.ConsoleOut
import Interpreter.InterpretOne
import flawless.data.Suite
import flawless.NoEffect
import cats.data.NonEmptyList
import cats.mtl.MonadState
import flawless.eval.unique.Unique
import cats.kernel.Eq
import cats.effect.Sync
import cats.Applicative
import cats.effect.concurrent.Ref
import cats.FlatMap
import cats.MonadError
import scala.util.control.NonFatal

@finalAlg
trait Interpreter[F[_]] {

  /**
    * Interprets the test structure to the underlying effect. This is where all the actual execution happens.
    */
  def interpret(reporter: Reporter[F]): InterpretOne[Suite, F]
}

object Interpreter {
  //A type alias for an action that interprets a single instance of Algebra (e.g. suite or test)
  type InterpretOne[Algebra[_[_]], F[_]] = Algebra[F] => F[Algebra[NoEffect]]

  def defaultInterpreter[F[_]: MonadError[*[_], Throwable]]: Interpreter[F] =
    new Interpreter[F] {

      private def interpretTest(implicit reporter: Reporter[F]): InterpretOne[Test, F] = { test =>
        def finish(results: Assertion): Test[NoEffect] = Test(test.name, TestRun.Pure(results))

        val exec: F[Test[NoEffect]] = test.result match {
          //this is a GADT skolem - you think I'd know what that means by now...
          case eval: TestRun.Eval[f] => eval.effect.handleError(Assertion.thrown(_)).map(finish)
          case TestRun.Pure(result)  => finish(result).pure[F]
          case TestRun.Lazy(e) =>
            finish {
              try e.value
              catch { case NonFatal(e) => (Assertion.thrown(e)) }
            }.pure[F]
        }

        reporter.publish(Reporter.Event.TestStarted(test.name)) *>
          exec <*
          reporter.publish(Reporter.Event.TestFinished(test.name))
      }

      private def interpretSuite(reporter: Reporter[F])(id: reporter.Identifier): InterpretOne[Suite.algebra.One, F] = {
        suite =>
          def finish(results: NonEmptyList[Test[NoEffect]]): Suite.algebra.One[NoEffect] =
            Suite.algebra.One(suite.name, results)

          reporter.publish(Reporter.Event.SuiteStarted(suite.name, id)) *>
            suite.tests.nonEmptyTraverse(interpretTest(reporter).apply(_)).map(finish).flatTap { suiteResult =>
              //todo duplicated logic!!!!
              val isSuccessful =
                suiteResult
                  .tests
                  .map(_.result.assertions[cats.Id])
                  .flatMap(_.results.toNonEmptyList)
                  .forall(_.isSuccessful)

              reporter.publish(Reporter.Event.SuiteFinished(suite.name, id, isSuccessful))
            }
      }

      import Suite.algebra._

      def interpret(reporter: Reporter[F]): InterpretOne[Suite, F] = {
        def interpretOne(parentId: reporter.Identifier): InterpretOne[Suite, F] = {
          case s: Sequence[f] =>
            type IdentifiedSuites = NonEmptyList[(Suite[f], reporter.Identifier)]

            val reportSuites: IdentifiedSuites => f[Unit] = suites =>
              reporter.publish(Reporter.Event.ReplaceSuiteWith(parentId, suites.map(_._2)))

            val interpretSuites: IdentifiedSuites => f[NonEmptyList[Suite[NoEffect]]] =
              s.traversal.traverse(_) { case (suite, id) => interpretOne(id)(suite) }

            s.suites
              .traverse((reporter.ident: f[reporter.Identifier]).tupleLeft(_))
              .flatTap(reportSuites)
              .flatMap(interpretSuites)
              .map(Suite.sequence[f](_))
          case o: One[f]       => interpretSuite(reporter)(parentId)(o)
          case s: Suspend[f]   => s.suite.flatMap(interpretOne(parentId))
          case r: RResource[f] => r.resuite.use(interpretOne(parentId))(r.bracket)
        }

        s => reporter.ident.flatMap(interpretOne(_)(s))
      }
    }
}

@finalAlg
trait Reporter[F[_]] {
  type Identifier
  def ident: F[Identifier]
  def publish(event: Reporter.Event[Identifier]): F[Unit]
}

object Reporter {
  sealed trait Event[Identifier] extends Product with Serializable

  object Event {
    final case class TestStarted[Identifier](name: String) extends Event[Identifier]
    final case class TestFinished[Identifier](name: String) extends Event[Identifier]
    final case class SuiteStarted[Identifier](name: String, id: Identifier) extends Event[Identifier]

    final case class SuiteFinished[Identifier](name: String, id: Identifier, succeeded: Boolean)
      extends Event[Identifier]

    final case class ReplaceSuiteWith[Identifier](replace: Identifier, withSuites: NonEmptyList[Identifier])
      extends Event[Identifier]

    implicit def eq[Identifier]: Eq[Event[Identifier]] = Eq.fromUniversalEquals
  }

  final case class SuiteHistory(cells: List[SuiteHistory.Cell]) {

    //reference implementation, will be overridden for more performance (and possibly no fs2 dependency)
    def stringify: String = {
      fs2.Stream.emit(Console.RESET) ++
        fs2.Stream.emits(cells).groupAdjacentBy(_.status).map(_.map(_.size)).map {
          case (status, cellCount) => status.color ++ status.stringify.combineN(cellCount)
        } ++
        fs2.Stream.emit(Console.RESET)
    }.compile.string
  }

  object SuiteHistory {

    final case class Cell(id: Unique, status: Status)

    sealed trait Status extends Product with Serializable {
      import Status._

      def stringify: String = this match {
        case Status.Pending => "▫"
        case _              => "◼"
      }

      def color: String = this match {
        case Pending   => Console.RESET
        case Running   => Console.YELLOW
        case Succeeded => Console.GREEN
        case Failed    => Console.RED
      }
    }

    object Status {
      case object Pending extends Status
      case object Running extends Status
      case object Succeeded extends Status
      case object Failed extends Status

      implicit val eq: Eq[Status] = Eq.fromUniversalEquals
    }

    val initial: SuiteHistory = SuiteHistory(Nil)

    type MState[F[_]] = MonadState[F, SuiteHistory]
    def MState[F[_]](implicit F: MState[F]): MState[F] = F

    def replace[F[_]: MState](toRemove: Unique, cells: NonEmptyList[Cell]): F[Unit] =
      MState[F].modify(
        c =>
          c.copy(c.cells.filter {
            case Cell(`toRemove`, Status.Pending) => false
            case _                                => true
          } ++ cells.toList)
      )

    def markRunning[F[_]: MState](id: Unique): F[Unit] = updateStatus[F] {
      case Cell(`id`, SuiteHistory.Status.Pending) => SuiteHistory.Status.Running
      case cell                                    => cell.status
    }

    def markFinished[F[_]: MState](id: Unique, succ: Boolean): F[Unit] = updateStatus[F] {
      case Cell(`id`, SuiteHistory.Status.Running) =>
        if (succ) SuiteHistory.Status.Succeeded else SuiteHistory.Status.Failed
      case cell => cell.status
    }

    //sub-optimal map, could stop early
    //todo rename to *firstStatus when changed
    def updateStatus[F[_]: MState](update: Cell => Status): F[Unit] =
      MState[F].modify { history =>
        history.copy(
          cells = history.cells.map { cell =>
            SuiteHistory.Cell(cell.id, update(cell))
          }
        )
      }

    def show[F[_]: MState: FlatMap: ConsoleOut]: F[Unit] =
      MState[F].get.flatMap { result =>
        val clear = "\u001b[2J\u001b[H"

        if (result.cells.map(_.status).contains_(Status.Pending))
          ConsoleOut[F].putStrLn(clear ++ result.stringify)
        else ConsoleOut[F].putStrLn(clear ++ "Finished")
      }
  }

  def consoleInstance[F[_]: Sync: ConsoleOut]: F[Reporter[F]] = Ref[F].of(0).map { identifiers =>
    new Reporter[F] {
      type Identifier = Int
      val ident: F[Identifier] = identifiers.modify(a => (a + 1, a))

      private def putStrWithDepth(depth: Int): String => F[Unit] = s => ConsoleOut[F].putStrLn(" " * depth * 2 + s)

      private val putSuite = putStrWithDepth(0)
      private val putTest = putStrWithDepth(1)

      def publish(event: Event[Identifier]): F[Unit] = event match {
        case Event.TestStarted(name)      => putTest(show"Starting test: $name")
        case Event.TestFinished(name)     => putTest(show"Finished test: $name")
        case Event.SuiteStarted(name, id) => putSuite(show"Starting suite: $name with id $id")
        case Event.SuiteFinished(name, id, succ) =>
          putSuite(show"Finished suite: $name with id $id. Succeeded? $succ")
        case Event.ReplaceSuiteWith(toRemove, toReplace) => putSuite(show"Replacing suite $toRemove with $toReplace")
      }
    }
  }

  import com.olegpy.meow.effects._

  def visual[F[_]: Sync: ConsoleOut]: F[Reporter[F]] =
    Ref[F].of(SuiteHistory.initial).map(_.stateInstance).map { implicit S =>
      new Reporter[F] {
        type Identifier = Unique
        val ident: F[Unique] = Sync[F].delay(new Unique)

        def publish(event: Event[Identifier]): F[Unit] = {
          event match {
            case Event.SuiteStarted(_, id)        => SuiteHistory.markRunning(id)
            case Event.SuiteFinished(_, id, succ) => SuiteHistory.markFinished(id, succ)
            case Event.ReplaceSuiteWith(toRemove, toReplace) =>
              val newCells: NonEmptyList[SuiteHistory.Cell] =
                toReplace.tupleRight(SuiteHistory.Status.Pending).map(SuiteHistory.Cell.tupled)
              SuiteHistory.replace(toRemove, newCells)

            case _ => Applicative[F].unit
          }
        } *> SuiteHistory.show
      }
    }
}