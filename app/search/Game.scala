package lila
package search

import game.DbGame
import chess.OpeningExplorer

import org.joda.time.format.{ DateTimeFormat, DateTimeFormatter }

object Game {

  object fields {
    val status = "st"
    val turns = "tu"
    val rated = "ra"
    val variant = "va"
    val uids = "ui"
    val averageElo = "el"
    val ai = "ai"
    val opening = "op"
    val date = "da"
    val duration = "du"
  }
  import fields._

  def mapping = {
    def field(name: String, typ: String, analyzed: Boolean = false, attrs: Map[String, Any] = Map.empty) =
      name -> (Map(
        "type" -> typ,
        "index" -> analyzed.fold("analyzed", "not_analyzed")
      ) ++ attrs)
    def obj(name: String, properties: Map[String, Any]) =
      name -> Map("type" -> "object", "properties" -> properties)
    Map(
      "properties" -> List(
        field(status, "short"),
        field(turns, "short"),
        field(rated, "boolean"),
        field(variant, "short"),
        field(uids, "string"),
        field(averageElo, "short"),
        field(ai, "short"),
        field(opening, "string"),
        field(date, "date", attrs = Map("format" -> dateFormat)),
        field(duration, "short")
      ).toMap
    )
  }

  def from(game: DbGame) = for {
    createdAt ← game.createdAt
  } yield game.id -> (List(
    status -> game.status.id.some,
    turns -> game.turns.some,
    rated -> game.rated.some,
    variant -> game.variant.id.some,
    uids -> (game.userIds.toNel map (_.list)),
    averageElo -> game.averageUsersElo,
    ai -> game.aiLevel,
    date -> (dateFormatter print createdAt).some,
    duration -> game.estimateTotalTime.some,
    opening -> (OpeningExplorer openingOf game.pgn map (_.code.toLowerCase))
  ) collect {
      case (x, Some(y)) ⇒ x -> y
    }).toMap

  private val dateFormat = "YYYY-MM-dd HH:mm:ss"
  val dateFormatter: DateTimeFormatter = DateTimeFormat forPattern dateFormat
}