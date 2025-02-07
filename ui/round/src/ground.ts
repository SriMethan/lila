import { h } from 'snabbdom';
import { Chessground } from 'chessground';
import * as cg from 'chessground/types';
import { oppositeOrientationForLOA, orientationForLOA } from 'chessground/util';
import { Api as CgApi } from 'chessground/api';
import { Config } from 'chessground/config';
import changeColorHandle from 'common/coordsColor';
import resizeHandle from 'common/resize';
import * as util from './util';
import { plyStep } from './round';
import RoundController from './ctrl';
import { RoundData } from './interfaces';
import * as chessUtil from 'chess';

export function makeConfig(ctrl: RoundController): Config {
  const data = ctrl.data,
    hooks = ctrl.makeCgHooks(),
    step = plyStep(data, ctrl.ply),
    playing = ctrl.isPlaying(),
    variantKey = data.game.variant.key as cg.Variant,
    turnPlayerIndex = step.ply % 2 === 0 ? 'p1' : 'p2';
  return {
    fen: step.fen,
    orientation: boardOrientation(data, ctrl.flip),
    myPlayerIndex: data.player.playerIndex,
    turnPlayerIndex: turnPlayerIndex,
    lastMove: util.lastMove(data.onlyDropsVariant, step.uci),
    check: !!step.check,
    coordinates: data.pref.coords !== Prefs.Coords.Hidden,
    addPieceZIndex: ctrl.data.pref.is3d,
    highlight: {
      lastMove: data.pref.highlight,
      check: data.pref.highlight,
    },
    events: {
      move: hooks.onMove,
      dropNewPiece: hooks.onNewPiece,
      insert(elements) {
        resizeHandle(elements, ctrl.data.pref.resizeHandle, ctrl.ply);
        if (data.pref.coords === Prefs.Coords.Inside) changeColorHandle();
      },
    },
    movable: {
      free: false,
      playerIndex: playing ? data.player.playerIndex : undefined,
      dests: playing ? util.parsePossibleMoves(data.possibleMoves) : new Map(),
      showDests: data.pref.destination,
      rookCastle: data.pref.rookCastle,
      events: {
        after: hooks.onUserMove,
        afterNewPiece: hooks.onUserNewPiece,
      },
    },
    animation: {
      enabled: true,
      duration: data.pref.animationDuration,
    },
    premovable: {
      enabled: data.pref.enablePremove && !data.onlyDropsVariant,
      showDests: data.pref.destination,
      castle: data.game.variant.key !== 'antichess' && data.game.variant.key !== 'noCastling',
      events: {
        set: hooks.onPremove,
        unset: hooks.onCancelPremove,
      },
    },
    predroppable: {
      enabled: data.pref.enablePremove && ['crazyhouse', 'shogi', 'minishogi'].includes(data.game.variant.key),
      events: {
        set: hooks.onPredrop,
        unset() {
          hooks.onPredrop(undefined);
        },
      },
    },
    dropmode: {
      showDropDests: true,
      dropDests: playing ? chessUtil.readDropsByRole(data.possibleDropsByRole) : new Map(),
      active: data.onlyDropsVariant && playing ? true : false,
      piece:
        data.onlyDropsVariant && playing
          ? util.onlyDropsVariantPiece(data.game.variant.key, turnPlayerIndex)
          : undefined,
    },
    draggable: {
      enabled: data.pref.moveEvent !== Prefs.MoveEvent.Click,
      showGhost: data.pref.highlight,
    },
    selectable: {
      enabled: data.pref.moveEvent !== Prefs.MoveEvent.Drag,
    },
    drawable: {
      enabled: true,
      defaultSnapToValidMove: (playstrategy.storage.get('arrow.snap') || 1) != '0',
      pieces: {
        baseUrl:
          variantKey === 'shogi' || variantKey === 'minishogi'
            ? 'https://playstrategy.org/assets/piece/shogi/' +
              data.pref.pieceSet.filter(ps => ps.gameFamily === 'shogi')[0].name +
              '/'
            : variantKey === 'xiangqi' || variantKey === 'minixiangqi'
            ? 'https://playstrategy.org/assets/piece/xiangqi/' +
              data.pref.pieceSet.filter(ps => ps.gameFamily === 'xiangqi')[0].name +
              '/'
            : 'https://playstrategy.org/assets/piece/chess/' +
              data.pref.pieceSet.filter(ps => ps.gameFamily === 'chess')[0].name +
              '/',
      },
    },
    disableContextMenu: true,
    dimensions: data.game.variant.boardSize,
    variant: variantKey,
    chess960: data.game.variant.key === 'chess960',
    onlyDropsVariant: data.onlyDropsVariant,
  };
}

export function reload(ctrl: RoundController) {
  ctrl.chessground.set(makeConfig(ctrl));
}

export function promote(ground: CgApi, key: cg.Key, role: cg.Role) {
  const piece = ground.state.pieces.get(key);
  if (
    (piece && piece.role === 'p-piece' && ground.state.variant !== 'shogi' && ground.state.variant !== 'minishogi') ||
    (piece &&
      (ground.state.variant == 'shogi' || ground.state.variant == 'minishogi') &&
      piece.role !== 'k-piece' &&
      piece.role !== 'g-piece')
  ) {
    ground.setPieces(
      new Map([
        [
          key,
          {
            playerIndex: piece.playerIndex,
            role,
            promoted: true,
          },
        ],
      ])
    );
  }
}

export function boardOrientation(data: RoundData, flip: boolean): cg.Orientation {
  if (data.game.variant.key === 'racingKings') return flip ? 'p2' : 'p1';
  if (data.game.variant.key === 'linesOfAction' || data.game.variant.key === 'scrambledEggs') {
    return flip ? oppositeOrientationForLOA(data.player.playerIndex) : orientationForLOA(data.player.playerIndex);
  } else return flip ? data.opponent.playerIndex : data.player.playerIndex;
}

export function render(ctrl: RoundController) {
  return h('div.cg-wrap', {
    hook: util.onInsert(el => ctrl.setChessground(Chessground(el, makeConfig(ctrl)))),
  });
}
