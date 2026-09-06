package com.schulzcode.y2remote.protocol

import org.json.JSONObject
import java.util.UUID

sealed class RemoteCommand {
    object Toggle : RemoteCommand() {
        override fun toString(): String = "Toggle"
    }
    object Play : RemoteCommand() {
        override fun toString(): String = "Play"
    }
    object Pause : RemoteCommand() {
        override fun toString(): String = "Pause"
    }
    object Next : RemoteCommand() {
        override fun toString(): String = "Next"
    }
    object Previous : RemoteCommand() {
        override fun toString(): String = "Previous"
    }
    object VolumeUp : RemoteCommand() {
        override fun toString(): String = "VolumeUp"
    }
    object VolumeDown : RemoteCommand() {
        override fun toString(): String = "VolumeDown"
    }
    data class SetVolume(val percent: Int) : RemoteCommand()
    data class Rewind(val amountMs: Long = RemoteProtocol.DEFAULT_SEEK_STEP_MS) : RemoteCommand()
    data class Forward(val amountMs: Long = RemoteProtocol.DEFAULT_SEEK_STEP_MS) : RemoteCommand()
    data class Seek(val positionMs: Long) : RemoteCommand()
}

sealed class RemoteMessage {
    data class Hello(
        val device: String,
        val protocol: Int
    ) : RemoteMessage()

    data class PlayerState(
        val title: String,
        val artist: String,
        val album: String,
        val status: String,
        val positionMs: Long,
        val durationMs: Long,
        val volumePercent: Int = 100
    ) : RemoteMessage()

    data class Artwork(
        val base64: String
    ) : RemoteMessage()

    data class Command(
        val command: RemoteCommand
    ) : RemoteMessage()

    data class Unknown(val raw: String) : RemoteMessage()
}

object RemoteProtocol {
    const val PROTOCOL_VERSION = 1
    const val SERVICE_NAME = "Y2Remote"
    const val CONTROLLER_DEVICE_NAME = "Y2 Controller"
    const val DEFAULT_SEEK_STEP_MS = 10000L
    const val MAX_LINE_LENGTH = 131072

    val UUID_Y2_REMOTE: UUID = UUID.fromString("a9c336b4-2dfb-4f9e-a89e-21ef1c60f4e1")
    val UUID_STANDARD_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val STATUS_PLAYING = "playing"
    const val STATUS_PAUSED = "paused"
    const val STATUS_STOPPED = "stopped"

    const val TYPE_HELLO = "hello"
    const val TYPE_STATE = "state"
    const val TYPE_ARTWORK = "artwork"
    const val TYPE_COMMAND = "command"

    const val CMD_TOGGLE = "toggle"
    const val CMD_PLAY = "play"
    const val CMD_PAUSE = "pause"
    const val CMD_NEXT = "next"
    const val CMD_PREVIOUS = "previous"
    const val CMD_VOLUME_UP = "volume_up"
    const val CMD_VOLUME_DOWN = "volume_down"
    const val CMD_SET_VOLUME = "set_volume"
    const val CMD_REWIND = "rewind"
    const val CMD_FORWARD = "forward"
    const val CMD_SEEK = "seek"

    fun encodeHello(device: String = CONTROLLER_DEVICE_NAME, protocol: Int = PROTOCOL_VERSION): String {
        val json = JSONObject()
        json.put("type", TYPE_HELLO)
        json.put("device", device)
        json.put("protocol", protocol)
        return json.toString()
    }

    fun encodeCommand(command: RemoteCommand): String {
        val json = JSONObject()
        json.put("type", TYPE_COMMAND)
        when (command) {
            is RemoteCommand.Toggle -> json.put("command", CMD_TOGGLE)
            is RemoteCommand.Play -> json.put("command", CMD_PLAY)
            is RemoteCommand.Pause -> json.put("command", CMD_PAUSE)
            is RemoteCommand.Next -> json.put("command", CMD_NEXT)
            is RemoteCommand.Previous -> json.put("command", CMD_PREVIOUS)
            is RemoteCommand.VolumeUp -> json.put("command", CMD_VOLUME_UP)
            is RemoteCommand.VolumeDown -> json.put("command", CMD_VOLUME_DOWN)
            is RemoteCommand.SetVolume -> {
                json.put("command", CMD_SET_VOLUME)
                json.put("percent", command.percent.coerceIn(0, 100))
            }
            is RemoteCommand.Rewind -> {
                json.put("command", CMD_REWIND)
                json.put("amount", command.amountMs)
            }
            is RemoteCommand.Forward -> {
                json.put("command", CMD_FORWARD)
                json.put("amount", command.amountMs)
            }
            is RemoteCommand.Seek -> {
                json.put("command", CMD_SEEK)
                json.put("position", command.positionMs)
            }
        }
        return json.toString()
    }

    fun parseMessage(line: String): RemoteMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LINE_LENGTH) return null
        return runCatching {
            val json = JSONObject(trimmed)
            when (json.optString("type")) {
                TYPE_HELLO -> {
                    val device = json.optString("device", "Unknown")
                    val protocol = json.optInt("protocol", 1)
                    RemoteMessage.Hello(device = device, protocol = protocol)
                }
                TYPE_STATE -> {
                    RemoteMessage.PlayerState(
                        title = json.optString("title", ""),
                        artist = json.optString("artist", ""),
                        album = json.optString("album", ""),
                        status = json.optString("status", STATUS_STOPPED),
                        positionMs = json.optLong("position", 0L),
                        durationMs = json.optLong("duration", 0L),
                        volumePercent = json.optInt("volume", 100)
                    )
                }
                TYPE_ARTWORK -> {
                    val data = json.optString("data", "")
                    if (data.isNotEmpty()) RemoteMessage.Artwork(data) else null
                }
                TYPE_COMMAND -> {
                    val cmd = parseCommand(json) ?: return null
                    RemoteMessage.Command(cmd)
                }
                else -> RemoteMessage.Unknown(trimmed)
            }
        }.getOrNull()
    }

    fun parseCommand(json: JSONObject): RemoteCommand? {
        return when (json.optString("command")) {
            CMD_TOGGLE -> RemoteCommand.Toggle
            CMD_PLAY -> RemoteCommand.Play
            CMD_PAUSE -> RemoteCommand.Pause
            CMD_NEXT -> RemoteCommand.Next
            CMD_PREVIOUS -> RemoteCommand.Previous
            CMD_VOLUME_UP -> RemoteCommand.VolumeUp
            CMD_VOLUME_DOWN -> RemoteCommand.VolumeDown
            CMD_SET_VOLUME -> RemoteCommand.SetVolume(json.optInt("percent", 100))
            CMD_REWIND -> RemoteCommand.Rewind(json.optLong("amount", DEFAULT_SEEK_STEP_MS))
            CMD_FORWARD -> RemoteCommand.Forward(json.optLong("amount", DEFAULT_SEEK_STEP_MS))
            CMD_SEEK -> RemoteCommand.Seek(json.optLong("position", 0L))
            else -> null
        }
    }
}
