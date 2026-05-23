package com.majeur.psclient.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class BattleRoomInfo(val roomId: String, val p1: String, val p2: String, val minElo: Int) : Parcelable