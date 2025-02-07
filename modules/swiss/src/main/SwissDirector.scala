package lila.swiss

import strategygames.{ P2, Player => PlayerIndex, P1, GameLogic }
import strategygames.variant.Variant
import strategygames.format.FEN
import org.joda.time.DateTime
import scala.util.chaining._
import scala.util.Random

import lila.db.dsl._
import lila.game.Game
import lila.user.User

final private class SwissDirector(
    colls: SwissColls,
    pairingSystem: PairingSystem,
    gameRepo: lila.game.GameRepo,
    onStart: Game.ID => Unit
)(implicit
    ec: scala.concurrent.ExecutionContext,
    idGenerator: lila.game.IdGenerator
) {
  import BsonHandlers._

  // sequenced by SwissApi
  private[swiss] def startRound(from: Swiss): Fu[Option[Swiss]] =
    pairingSystem(from)
      .flatMap { pendings =>
        val pendingPairings = pendings.collect { case Right(p) => p }
        if (pendingPairings.isEmpty) fuccess(none) // terminate
        else {
          val swiss = from.startRound
          val drawTableList: List[FEN] = if (swiss.settings.useDrawTables) swiss.variant match {
            case Variant.Draughts(variant) =>
              strategygames.draughts.OpeningTable.tablesForVariant(variant).map(FEN.Draughts)
            case _ => List()
          }
          else List()
          val openingFEN = if (drawTableList.isEmpty) swiss.settings.position
          else drawTableList(Random.nextInt(drawTableList.size)).some
          for {
            players <- SwissPlayer.fields { f =>
              colls.player.list[SwissPlayer]($doc(f.swissId -> swiss.id))
            }
            ids <- idGenerator.games(pendingPairings.size)
            pairings = pendingPairings.zip(ids).map { case (SwissPairing.Pending(w, b), id) =>
              SwissPairing(
                id = id,
                swissId = swiss.id,
                round = swiss.round,
                p1 = w,
                p2 = b,
                status = Left(SwissPairing.Ongoing),
                isMicroMatch = swiss.settings.isMicroMatch,
                None,
                openingFEN
              )
            }
            _ <-
              colls.swiss.update
                .one(
                  $id(swiss.id),
                  $unset("nextRoundAt") ++ $set(
                    "round"       -> swiss.round,
                    "nbOngoing"   -> pairings.size,
                    "lastRoundAt" -> DateTime.now
                  )
                )
                .void
            date = DateTime.now
            byes = pendings.collect { case Left(bye) => bye.player }
            _ <- SwissPlayer.fields { f =>
              colls.player.update
                .one($doc(f.userId $in byes, f.swissId -> swiss.id), $addToSet(f.byes -> swiss.round))
                .void
            }
            _ <- colls.pairing.insert.many(pairings).void
            games = pairings.map(makeGame(swiss, SwissPlayer.toMap(players)))
            _ <- lila.common.Future.applySequentially(games) { game =>
              gameRepo.insertDenormalized(game) >>- onStart(game.id)
            }
          } yield swiss.some
        }
      }
      .recover { case PairingSystem.BBPairingException(msg, input) =>
        if (msg contains "The number of rounds is larger than the reported number of rounds.") none
        else {
          logger.warn(s"BBPairing ${from.id} $msg")
          logger.info(s"BBPairing ${from.id} $input")
          from.some
        }
      }
      .monSuccess(_.swiss.startRound)

  private[swiss] def makeGame(swiss: Swiss, players: Map[User.ID, SwissPlayer], rematch: Boolean = false)(
      pairing: SwissPairing
  ): Game =
    Game
      .make(
        chess = strategygames.Game(
          swiss.variant.gameLogic,
          variant = Some {
            if (swiss.settings.position.isEmpty) swiss.variant
            else Variant.libFromPosition(swiss.variant.gameLogic)
          },
          fen = pairing.openingFEN
        ) pipe { g =>
          val turns = g.player.fold(0, 1)
          g.copy(
            clock = swiss.clock.toClock.some,
            turns = turns,
            startedAtTurn = turns
          )
        },
        p1Player = makePlayer(P1, players.get(if(rematch) pairing.p2 else pairing.p1) err s"Missing pairing p1 $pairing"),
        p2Player = makePlayer(P2, players.get(if(rematch) pairing.p1 else pairing.p2) err s"Missing pairing p2 $pairing"),
        mode = strategygames.Mode(swiss.settings.rated),
        source = lila.game.Source.Swiss,
        pgnImport = None
      )
      .withId(if (rematch) pairing.microMatchGameId.getOrElse(pairing.gameId) else pairing.id)
      .withSwissId(swiss.id.value)
      .start

  private def makePlayer(playerIndex: PlayerIndex, player: SwissPlayer) =
    lila.game.Player.make(playerIndex, player.userId, player.rating, player.provisional)
}
