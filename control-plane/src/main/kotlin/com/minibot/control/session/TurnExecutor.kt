package com.minibot.control.session

interface TurnExecutor {
    suspend fun execute(turn: TurnRecord, session: SessionRecord)
}
