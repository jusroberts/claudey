package com.wiggletonabbey.wigglebot.car

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.wiggletonabbey.wigglebot.service.ChannelEvent
import com.wiggletonabbey.wigglebot.service.PhoenixChannelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "DriveScreen"

/**
 * Main Android Auto screen — shows conversation history and an "Ask" button.
 * Tapping "Ask" pushes InputScreen (a SearchTemplate) for voice/keyboard input.
 * Responses are displayed on-screen and read aloud via TTS.
 */
class DriveScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val channelService = PhoenixChannelService(carContext)

    // (isUser, text) — capped at 6 so we never exceed template row limits
    private val messages = mutableListOf<Pair<Boolean, String>>()
    private var isThinking = false

    private var ttsReady = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(carContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                ttsReady = true
            }
        }
        channelService.connect()

        scope.launch {
            channelService.events.collect { event ->
                when (event) {
                    is ChannelEvent.Thinking -> Unit
                    is ChannelEvent.AssistantMessage -> {
                        messages.add(false to event.text)
                        if (messages.size > 6) messages.subList(0, messages.size - 6).clear()
                        if (ttsReady) tts.speak(event.text, TextToSpeech.QUEUE_FLUSH, null, "wigglebot_reply")
                        isThinking = false
                        invalidate()
                    }
                    is ChannelEvent.ToolActivity -> Unit
                    is ChannelEvent.Error -> {
                        Log.e(TAG, "Channel error: ${event.message}")
                        messages.add(false to "Sorry, something went wrong: ${event.message}")
                        if (messages.size > 6) messages.subList(0, messages.size - 6).clear()
                        isThinking = false
                        invalidate()
                    }
                }
            }
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
                tts.shutdown()
            }
        })
    }

    fun onUserInput(text: String) {
        if (isThinking) return
        messages.add(true to text)
        isThinking = true
        invalidate()
        channelService.sendMessage(text)
    }

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        if (messages.isEmpty() && !isThinking) {
            itemListBuilder.setNoItemsMessage(
                "Tap \"Ask\" to talk to WiggleBot. Try: \"What's the weather?\" or \"Find a gas station nearby.\""
            )
        }

        messages.forEach { (isUser, text) ->
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle(text.take(120))
                    .addText(if (isUser) "You" else "WiggleBot")
                    .build()
            )
        }

        if (isThinking) {
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("Thinking…")
                    .addText("WiggleBot")
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("WiggleBot")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(if (isThinking) "…" else "Ask")
                            .setEnabled(!isThinking)
                            .setOnClickListener {
                                screenManager.push(InputScreen(carContext, ::onUserInput))
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}

/**
 * Voice/keyboard input screen. Uses SearchTemplate which Android Auto
 * automatically wires to the car's microphone button.
 */
class InputScreen(
    carContext: CarContext,
    private val onSubmit: (String) -> Unit,
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(query: String) {
                if (query.isNotBlank()) onSubmit(query)
                screenManager.pop()
            }
            override fun onSearchTextChanged(query: String) {}
        })
        .setHeaderAction(Action.BACK)
        .setShowKeyboardByDefault(false)
        .setInitialSearchText("")
        .build()
}
