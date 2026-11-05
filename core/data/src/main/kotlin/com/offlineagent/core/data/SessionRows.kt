package com.offlineagent.core.data

import com.offlineagent.core.runtime.chat.StoredMessage

/** 0.6.2.1: Room rows → the pure-Kotlin session model (the chat context is always rebuilt from the database). */
fun MessageEntity.toStored(): StoredMessage = StoredMessage(id, role, text, kind, reasoning, uiJson, error, runId)

suspend fun ChatDao.storedMessages(chatId: Long): List<StoredMessage> = messages(chatId).map { it.toStored() }
