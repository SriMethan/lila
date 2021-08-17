package lila.setup

import strategygames.Color.{ Black, White }
import strategygames.{ Game => StratGame, GameLib }
import strategygames.format.FEN
import strategygames.variant.Variant
import lila.game.{ Game, Player, Pov, Source }
import lila.lobby.Color
import lila.user.User

case class AiConfig(
    variant: strategygames.variant.Variant,
    fenVariant: Option[Variant],
    timeMode: TimeMode,
    time: Double,
    increment: Int,
    days: Int,
    level: Int,
    color: Color,
    fen: Option[FEN] = None
) extends Config
    with Positional {

  val strictFen = true

  def >> = (variant.gameLib.id, variant.id, variant.id, timeMode.id, time, increment, days, level, color.name, fen.map(_.value)).some

  def game(user: Option[User]) =
    fenGame { chessGame =>
      val perfPicker = lila.game.PerfPicker.mainOrDefault(
        strategygames.Speed(chessGame.clock.map(_.config)),
        chessGame.situation.board.variant,
        makeDaysPerTurn
      )
      Game
        .make(
          chess = chessGame,
          whitePlayer = creatorColor.fold(
            Player.make(White, user, perfPicker),
            Player.make(White, level.some)
          ),
          blackPlayer = creatorColor.fold(
            Player.make(Black, level.some),
            Player.make(Black, user, perfPicker)
          ),
          mode = strategygames.Mode.Casual,
          source = if (chessGame.board.variant.fromPosition) Source.Position else Source.Ai,
          daysPerTurn = makeDaysPerTurn,
          pgnImport = None
        )
        .sloppy
    } start

  def pov(user: Option[User]) = Pov(game(user), creatorColor)

  def timeControlFromPosition = variant != strategygames.chess.variant.FromPosition || time >= 1
}

object AiConfig extends BaseConfig {

  def from(l: Int, cv: Int, dv: Int, tm: Int, t: Double, i: Int, d: Int, level: Int, c: String, fen: Option[String]) =
    new AiConfig(
      variant = l match {
        case 0 => Variant.wrap(
          strategygames.chess.variant.Variant(cv) err "Invalid game variant " + cv
        )
        case 1 => Variant.wrap(
          strategygames.draughts.variant.Variant(dv) err "Invalid game variant " + dv
        )
      },
      fenVariant = none,
      timeMode = TimeMode(tm) err s"Invalid time mode $tm",
      time = t,
      increment = i,
      days = d,
      level = level,
      color = Color(c) err "Invalid color " + c,
      fen = fen.map(f => FEN.apply(GameLib(l), f))
    )

  def default(l: Int) = AiConfig(
    variant = l match {
      case 0 => Variant.Chess(chessVariantDefault)
      case 1 => Variant.Draughts(draughtsVariantDefault)
    },
    fenVariant = none,
    timeMode = TimeMode.Unlimited,
    time = 5d,
    increment = 8,
    days = 2,
    level = 1,
    color = Color.default
  )

  val levels = (1 to 8).toList

  val levelChoices = levels map { l =>
    (l.toString, l.toString, none)
  }

  import lila.db.BSON
  import lila.db.dsl._

  implicit private[setup] val aiConfigBSONHandler = new BSON[AiConfig] {

    def reads(r: BSON.Reader): AiConfig =
      AiConfig(
        variant = strategygames.variant.Variant.orDefault(GameLib(r intD "l"), r int "v"),
        fenVariant = none,
        timeMode = TimeMode orDefault (r int "tm"),
        time = r double "t",
        increment = r int "i",
        days = r int "d",
        level = r int "l",
        color = Color.White,
        fen = r.getO[FEN]("f").filter(_.value.nonEmpty)
      )

    def writes(w: BSON.Writer, o: AiConfig) =
      $doc(
        "v"  -> o.variant.id,
        "tm" -> o.timeMode.id,
        "t"  -> o.time,
        "i"  -> o.increment,
        "d"  -> o.days,
        "l"  -> o.level,
        "f"  -> o.fen
      )
  }
}
