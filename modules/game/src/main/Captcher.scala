package lila.game

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor._
import akka.pattern.{ ask, pipe }
import chess.format.{ Forsyth, pgn }
import chess.{ Game ⇒ ChessGame, Color }
import play.api.libs.concurrent.Akka.system
import play.api.Play.current
import scalaz.{ NonEmptyList, OptionT }
import spray.caching.{ LruCache, Cache }

import lila.common.Captcha, Captcha._
import lila.db.api.$find
import lila.hub.actorApi.captcha._
import tube.gameTube

// only works with standard chess (not chess960)
private final class Captcher extends Actor {

  def receive = {

    case AnyCaptcha             ⇒ sender ! Impl.current

    case GetCaptcha(id: String) ⇒ sender ! Impl.get(id).await

    case actorApi.NewCaptcha    ⇒ Impl.refresh.await

    case ValidCaptcha(id: String, solution: String) ⇒
      Impl get id map (_ valid solution) pipeTo sender
  }

  private object Impl {

    def get(id: String): Fu[Captcha] = find(id) match {
      case None    ⇒ getFromDb(id) map (c ⇒ (c | Captcha.default) ~ add)
      case Some(c) ⇒ fuccess(c)
    }

    def current = challenges.head

    def refresh: Funit = createFromDb ~ (_ onSuccess {
      case Some(captcha) ⇒ add(captcha)
    }) void

    // Private stuff

    private val capacity = 512
    private var challenges: NonEmptyList[Captcha] = NonEmptyList(Captcha.default)

    private def add(c: Captcha) {
      find(c.gameId) ifNone {
        challenges = NonEmptyList.nel(c, challenges.list take capacity)
      }
    }

    private def find(id: String): Option[Captcha] =
      challenges.list.find(_.gameId == id)

    private def createFromDb: Fu[Option[Captcha]] =
      optionT(findCheckmateInDb(10) flatMap {
        _.fold(findCheckmateInDb(1))(g ⇒ fuccess(g.some))
      }) flatMap fromGame

    private def findCheckmateInDb(distribution: Int): Fu[Option[Game]] =
      GameRepo findRandomStandardCheckmate distribution

    private def getFromDb(id: String): Fu[Option[Captcha]] =
      optionT($find byId id) flatMap fromGame

    private def fromGame(game: Game): OptionT[Fu, Captcha] =
      optionT(PgnRepo getOption game.id) flatMap { makeCaptcha(game, _) }

    private def makeCaptcha(game: Game, pgnString: String): OptionT[Fu, Captcha] =
      optionT(Future {
        for {
          rewinded ← rewind(game, pgnString)
          solutions ← solve(rewinded)
        } yield Captcha(game.id, fen(rewinded), rewinded.player.white, solutions)
      })

    private def solve(game: ChessGame): Option[Captcha.Solutions] =
      game.situation.moves.toList flatMap {
        case (_, moves) ⇒ moves filter { move ⇒
          (move.after situationOf !game.player).checkMate
        }
      } map (_.notation) toNel

    private def rewind(game: Game, pgnString: String): Option[ChessGame] =
      pgn.Reader.withSans(pgnString, safeInit) map (_.game) toOption

    private def safeInit[A](list: List[A]): List[A] = list match {
      case x :: Nil ⇒ Nil
      case x :: xs  ⇒ x :: safeInit(xs)
      case _        ⇒ Nil
    }

    private def fen(game: ChessGame): String = Forsyth >> game takeWhile (_ != ' ')
  }
}
