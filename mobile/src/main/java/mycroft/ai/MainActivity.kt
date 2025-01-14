/*
 *  Copyright (c) 2017. Mycroft AI, Inc.
 *
 *  This file is part of Mycroft-Android a client for Mycroft Core.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package mycroft.ai

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.preference.PreferenceManager
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.crashlytics.android.Crashlytics
import io.fabric.sdk.android.Fabric
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import mycroft.ai.Constants.MycroftMobileConstants.VERSION_CODE_PREFERENCE_KEY
import mycroft.ai.Constants.MycroftMobileConstants.VERSION_NAME_PREFERENCE_KEY
import mycroft.ai.adapters.MycroftAdapter
import mycroft.ai.receivers.NetworkChangeReceiver
import mycroft.ai.services.PorcupineService
import mycroft.ai.shared.utilities.GuiUtilities.showToast
import mycroft.ai.utils.NetworkUtil
import org.java_websocket.client.WebSocketClient
import org.java_websocket.exceptions.WebsocketNotConnectedException
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.SpeechStreamService
import org.vosk.android.StorageService
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException


class MainActivity : AppCompatActivity(), RecognitionListener {
    // Mycroft Part
    private val logTag = "Mycroft"
    private val utterances = mutableListOf<Utterance>()
    private val reqCodeSpeechInput = 100
    private var maximumRetries = 1
    private var currentItemPosition = -1

    private var isNetworkChangeReceiverRegistered = false
    private var launchedFromWidget = false
    private var autoPromptForSpeech = false
    private var resultText = ""

    private lateinit var mycroftAdapter: MycroftAdapter
    private lateinit var wsip: String
    private lateinit var sharedPref: SharedPreferences
    private lateinit var networkChangeReceiver: NetworkChangeReceiver
    private lateinit var tts : TextToSpeech;

    var webSocketClient: WebSocketClient? = null

    private val STATE_READY = 0
    private val STATE_MIC = 1

    /* Used to handle permission request */
    private final val PERMISSIONS_REQUEST_RECORD_AUDIO = 1

    private lateinit var model : Model
    private var speechService: SpeechService? = null
    private var speechStreamService : SpeechStreamService? = null
    private lateinit var resultView : TextView

    private var bound : Boolean = false
    private lateinit var broadcastRec : BroadcastReceiver

    private lateinit var requestQueue : RequestQueue


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Fabric.with(this, Crashlytics())
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar as Toolbar?)

        loadPreferences()

        tts = TextToSpeech(this, wsip, "5002")

        mycroftAdapter = MycroftAdapter(utterances, applicationContext, menuInflater)
        mycroftAdapter.setOnLongItemClickListener(object: MycroftAdapter.OnLongItemClickListener {
            override fun itemLongClicked(v: View, position: Int) {
                currentItemPosition = position
                v.showContextMenu()
            }
        })

        kbMicSwitch.setOnCheckedChangeListener { _, isChecked ->
            val editor = sharedPref.edit()
            editor.putBoolean("kbMicSwitch", isChecked)
            editor.apply()

            if (isChecked) {
                // Switch to mic
                micButton.visibility = View.VISIBLE
                utteranceInput.visibility = View.INVISIBLE
                sendUtterance.visibility = View.INVISIBLE
            } else {
                // Switch to keyboard
                micButton.visibility = View.INVISIBLE
                utteranceInput.visibility = View.VISIBLE
                sendUtterance.visibility = View.VISIBLE
            }
        }

        registerForContextMenu(cardList)

        //attach a listener to check for changes in state
        voxswitch.setOnCheckedChangeListener { _, isChecked ->
            val editor = sharedPref.edit()
            editor.putBoolean("appReaderSwitch", isChecked)
            editor.apply()
        }

        val llm = LinearLayoutManager(this)
        llm.stackFromEnd = true
        llm.orientation = LinearLayoutManager.VERTICAL
        with (cardList) {
            setHasFixedSize(true)
            layoutManager = llm
            adapter = mycroftAdapter
        }

        registerReceivers()

        resultView = findViewById(R.id.result_text)
        setUiState(STATE_READY)
        LibVosk.setLogLevel(LogLevel.INFO)

        var permissionCheck = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO)
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,  arrayOf(Manifest.permission.RECORD_AUDIO) , PERMISSIONS_REQUEST_RECORD_AUDIO)
        } else {
            initModel()
            var intent = startPorcupine()
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

            var filter = IntentFilter()
            filter.addAction("thomicroft.recognizeMicrophone")
            broadcastRec = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {

                    showWakeWordToast()
                    recognizeMicrophone()
                }
            }
            registerReceiver(broadcastRec, filter)

            requestQueue = Volley.newRequestQueue(this)
        }

        // Textinput
        utteranceInput.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
            if(actionId == EditorInfo.IME_ACTION_DONE){
                sendUtterance()
                true
            } else {
                false
            }
        })
        micButton.setOnClickListener { recognizeMicrophone() }
        sendUtterance.setOnClickListener { sendUtterance() }

        //val testData ="Vor einem großen Walde wohnte ein armer Holzhacker mit seiner Frau und seinen zwei Kindern. das Bübchen hieß Hänsel und das Mädchen Gretel. Er hatte wenig zu beißen und zu brechen, und einmal, als große Teuerung ins Land kam, konnte er das tägliche Brot nicht mehr schaffen. Wie er sich nun abends im Bette Gedanken machte und sich vor Sorgen herumwälzte, seufzte er und sprach zu seiner Frau: Was soll aus uns werden? Wie können wir unsere armen Kinder ernähren da wir für uns selbst nichts mehr haben? - Weißt du was, Mann, antwortete die Frau, wir wollen morgen in aller Frühe die Kinder hinaus in den Wald führen, wo er am dicksten ist. Da machen wir ihnen ein Feuer an und geben jedem noch ein Stückchen Brot, dann gehen wir an unsere Arbeit und lassen sie allein. Sie finden den Weg nicht wieder nach Haus, und wir sind sie los. - Nein, Frau, sagte der Mann, das tue ich nicht; wie sollt ich's übers Herz bringen, meine Kinder im Walde allein zu lassen! Die wilden Tiere würden bald kommen und sie zerreißen. Oh, du Narr, sagte sie, dann müssen wir alle viere Hungers sterben, du kannst nur die Bretter für die Särge hobeln, und ließ ihm keine Ruhe, bis er einwilligte. Aber die armen Kinder dauern mich doch, sagte der Mann."
        //addData(Utterance( testData, UtteranceFrom.MYCROFT))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_setup, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        var consumed = false
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                consumed = true
            }
            R.id.action_home_mycroft_ai -> {
                val intent = Intent(Intent.ACTION_VIEW,
                        Uri.parse(getString(R.string.mycroft_website_url)))
                startActivity(intent)
            }
        }

        return consumed && super.onOptionsItemSelected(item)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        super.onContextItemSelected(item)
        if (item.itemId == R.id.user_resend) {
            // Resend user utterance
            sendMessage(utterances[currentItemPosition].utterance)
        } else if (item.itemId == R.id.user_copy || item.itemId == R.id.mycroft_copy) {
            // Copy utterance to clipboard
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val data = ClipData.newPlainText("text", utterances[currentItemPosition].utterance)
            clipboardManager.setPrimaryClip(data)
            showToast(this,"Copied to clipboard")
        } else if (item.itemId == R.id.mycroft_share) {
            // Share utterance
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, utterances[currentItemPosition].utterance)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, resources.getText(R.string.action_share)))
        } else {
            return super.onContextItemSelected(item)
        }

        return true
    }
    
    fun sendUtterance() {
        val utterance = utteranceInput.text.toString()
        if (utterance != "") {
            if (!possIntentsRepeatSkill.contains(utterance)) lastUtterance = utterance
            sendMessage(utterance)
            checkForSpecialSkills(utterance)
            utteranceInput.text.clear()
        }
    }

    fun sendUtterance(input : String) {
        if (input != "") {
            sendMessage(input)
            utteranceInput.text.clear()
        }
    }

    private var doRepeat : Boolean = false
    private var ttsSysnthesisLevel : Int = 1
    private var lastUtterance : String = ""
    private val possIntentsRepeatSkill = listOf<String>("Wiederholen", "Noch einmal", "Nochmal", "Wiederhole das", "Wiederhole das bitte", "Wiederhole")
    private val possIntentsDeutlicherSkill = listOf<String>("Deutlicher", "Deutlicher bitte", "Klarer", "Nochmal deutlicher", "Wiederholen deutlicher",
            "Deutlicher wiederholen", "Nochmal klarer", "Wiederhole klarer", "Klarer wiederholen", "Klarer bitte", "Verständlicher", "Verständlicher bitte",
            "Nochmal verständlicher", "Wiederhole verständlicher", "Verständlicher wiederholen")
    private fun checkForSpecialSkills(utterance : String) {

        if (possIntentsRepeatSkill.contains(utterance)) doRepeat = true
        //check for more
        if (possIntentsDeutlicherSkill.contains(utterance)) {

            ttsSysnthesisLevel += 1
        }

        if (doRepeat) {

            sendUtterance(lastUtterance)
            doRepeat = false
        }
        //if (something else...)
    }

    fun connectWebSocket() {
        val uri = deriveURI()

        if (uri != null) {
            webSocketClient = object : WebSocketClient(uri) {
                override fun onOpen(serverHandshake: ServerHandshake) {
                    Log.i("Websocket", "Opened")
                }

                override fun onMessage(s: String) {
                    // Log.i(TAG, s);
                    runOnUiThread(MessageParser(s, object : SafeCallback<Utterance> {
                        override fun call(param: Utterance) {
                            addData(param)
                        }
                    }))
                }

                override fun onClose(i: Int, s: String, b: Boolean) {
                    Log.i("Websocket", "Closed $s")

                }

                override fun onError(e: Exception) {
                    Log.i("Websocket", "Error " + e.message)
                }
            }
            webSocketClient!!.connect()
        }
    }

    private fun addData(mycroftUtterance: Utterance) {
        utterances.add(mycroftUtterance)
        defaultMessageTextView.visibility = View.GONE
        mycroftAdapter.notifyItemInserted(utterances.size - 1)
        if (voxswitch.isChecked) {
            if (mycroftUtterance.from.toString() != "USER") {

                var lastSentenceEnd = 0
                for (i in 1 until mycroftUtterance.utterance.length) {

                    var letter : Char = mycroftUtterance.utterance[i]
                    if (letter == '.' || letter == '!' || letter == '?') {

                        if (!tts.canSend) Thread.sleep(500)
                        var sentence = mycroftUtterance.utterance.subSequence(lastSentenceEnd, i + 1) as String
                        tts.sendTTSRequest(sentence)
                        lastSentenceEnd = i + 1
                    }
                }




                //tts.sendTTSRequest(mycroftUtterance.utterance)
            }
        }
        cardList.smoothScrollToPosition(mycroftAdapter.itemCount - 1)
    }

    private fun registerReceivers() {
        registerNetworkReceiver()
    }

    private fun registerNetworkReceiver() {
        if (!isNetworkChangeReceiverRegistered) {
            // set up the dynamic broadcast receiver for maintaining the socket
            networkChangeReceiver = NetworkChangeReceiver()
            networkChangeReceiver.setMainActivityHandler(this)

            // set up the intent filters
            val connChange = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
            val wifiChange = IntentFilter("android.net.wifi.WIFI_STATE_CHANGED")
            registerReceiver(networkChangeReceiver, connChange)
            registerReceiver(networkChangeReceiver, wifiChange)

            isNetworkChangeReceiverRegistered = true
        }
    }

    private fun unregisterReceivers() {
        unregisterBroadcastReceiver(networkChangeReceiver)

        isNetworkChangeReceiverRegistered = false
    }

    private fun unregisterBroadcastReceiver(broadcastReceiver: BroadcastReceiver) {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
    }

    /**
     * This method will attach the correct path to the
     * [.wsip] hostname to allow for communication
     * with a Mycroft instance at that address.
     *
     *
     * If [.wsip] cannot be used as a hostname
     * in a [URI] (e.g. because it's null), then
     * this method will return null.
     *
     *
     * @return a valid uri, or null
     */
    private fun deriveURI(): URI? {
        return if (wsip.isNotEmpty()) {
            try {
                URI("ws://$wsip:8181/core")
            } catch (e: URISyntaxException) {
                Log.e(logTag, "Unable to build URI for websocket", e)
                null
            }
        } else {
            null
        }
    }

    fun sendMessage(msg: String) {
        // sends message to the mycroft core
        //final String json = "{\"message_type\":\"recognizer_loop:utterance\", \"context\": null, \"metadata\": {\"utterances\": [\"" + msg + "\"]}}";
        val json = "{\"data\": {\"utterances\": [\"$msg\"]}, \"type\": \"recognizer_loop:utterance\", \"context\": null}"

        try {
            if (webSocketClient == null || webSocketClient!!.connection.isClosed) {
                // try and reconnect
                if (NetworkUtil.getConnectivityStatus(this) == NetworkUtil.NETWORK_STATUS_WIFI) { //TODO: add config to specify wifi only.
                    connectWebSocket()
                }
            }

            val handler = Handler()
            handler.postDelayed({
                // Actions to do after 1 seconds
                try {
                    webSocketClient!!.send(json)
                    addData(Utterance(msg, UtteranceFrom.USER))
                } catch (exception: WebsocketNotConnectedException) {
                    showToast(this, resources.getString(R.string.websocket_closed))
                    tts.playErrorMessage()
                } catch (exception: KotlinNullPointerException) {
                    showToast(this, resources.getString(R.string.websocket_null))
                    tts.playErrorMessage()
                }
            }, 1000)


        } catch (exception: WebsocketNotConnectedException) {
            showToast(this, resources.getString(R.string.websocket_closed))
        }
    }

    fun recognizeMicrophone() {
        // speech recognition

        // if: speech recognition is activated -> deactivate speech recognition and send message to text2num-server
        if (speechService != null) {
            speechService!!.stop()
            speechService = null
            parseNumber(resultText)
            setUiState(STATE_READY)
            resultText = ""
        // else: speech recognition is not activated -> activated speech recognition
        } else {
            playRecognitionChime()
            setUiState(STATE_MIC)
            try {
                val rec = Recognizer(model, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                speechService!!.startListening(this)
            } catch (e: IOException) {
                setErrorState(e.message!!)
            }
        }
    }

    /**
     * Receiving speech input
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            reqCodeSpeechInput -> {
                if (resultCode == Activity.RESULT_OK && null != data) {

                    val result = data
                            .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)

                    sendMessage(result[0])
                }
            }
        }
        setUiState(STATE_READY)
    }

    public override fun onDestroy() {
        super.onDestroy()
        isNetworkChangeReceiverRegistered = false

        if (speechService != null) {
            speechService!!.stop()
            speechService!!.shutdown()
        }

        if (speechStreamService != null) {
            speechStreamService!!.stop()
        }

        stopPorcupine()
        unregisterBroadcastReceiver(broadcastRec)
    }

    public override fun onStart() {
        super.onStart()
        recordVersionInfo()
        registerReceivers()
    }

    public override fun onStop() {
        super.onStop()

        unregisterReceivers()

        if (launchedFromWidget) {
            autoPromptForSpeech = true
        }

    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            bound = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun loadPreferences() {
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this)

        // get mycroft-core ip address
        wsip = sharedPref.getString("ip", "")!!
        if (wsip.isEmpty()) {
            // eep, show the settings intent!
            startActivity(Intent(this, SettingsActivity::class.java))
        } else if (webSocketClient == null || webSocketClient!!.connection.isClosed) {
            connectWebSocket()
        }

        kbMicSwitch.isChecked = sharedPref.getBoolean("kbMicSwitch", true)
        if (kbMicSwitch.isChecked) {
            // Switch to mic
            micButton.visibility = View.VISIBLE
            utteranceInput.visibility = View.INVISIBLE
            sendUtterance.visibility = View.INVISIBLE
        } else {
            // Switch to keyboard
            micButton.visibility = View.INVISIBLE
            utteranceInput.visibility = View.VISIBLE
            sendUtterance.visibility = View.VISIBLE
        }

        // set app reader setting
        voxswitch.isChecked = sharedPref.getBoolean("appReaderSwitch", true)

        maximumRetries = Integer.parseInt(sharedPref.getString("maximumRetries", "1")!!)
    }

    private fun recordVersionInfo() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val editor = sharedPref.edit()
            editor.putInt(VERSION_CODE_PREFERENCE_KEY, packageInfo.versionCode)
            editor.putString(VERSION_NAME_PREFERENCE_KEY, packageInfo.versionName)
            editor.apply()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(logTag, "Couldn't find package info", e)
        }
    }

    // initialize vosk model for speech recognition
    private fun initModel() {
        // initialize german model
        StorageService.unpack(this, "vosk-model-small-de-0.15", "model",
            { model: Model? ->
                this.model = model!!
                setUiState(STATE_READY)
            }
        ) { exception: IOException ->
            setErrorState(
                "Failed to unpack the model" + exception.message
            )
        }

    }

    private fun setErrorState(message: String) {
        resultView.text = message
        // setViewId stuff was deleted here
    }

    private fun setUiState(state: Int) {
        when (state) {
           STATE_READY -> {
               textfeld.visibility = View.INVISIBLE
               result_text.visibility = View.INVISIBLE
            }
            STATE_MIC -> {
                resultView.text = ""
                textfeld.visibility = View.VISIBLE
                result_text.visibility = View.VISIBLE
            }
            else -> throw IllegalStateException("Unexpected value: $state")
        }

    }

    override fun onPartialResult(hypothesis: String) {}

    override fun onResult(hypothesis : String) {
        var hypothesisText = JSONObject(hypothesis)["text"].toString()
        resultView.append(" $hypothesisText")

        resultText += " $hypothesisText"

    }

    override fun onFinalResult(hypothesis: String) {
        setUiState(STATE_READY)
        if (speechStreamService != null) {
            speechStreamService = null
        }

    }

    override fun onError(e: java.lang.Exception) {
        setErrorState(e.message!!)
    }

    override fun onTimeout() {
        setUiState(STATE_READY)
    }

    private fun pause(checked : Boolean) {
        if (speechService != null) {
            speechService!!.setPause(checked)
        }
    }

    private fun startPorcupine() : Intent? {
        intent = PorcupineService.startService(this, "Thomicroft Service is running")
        return intent
    }

    private fun stopPorcupine() : Intent? {
        intent = PorcupineService.stopService(this)
        return intent
    }

    fun showWakeWordToast() {
        showToast(this, "Wake Word erkannt!")
    }

    // converts written out numbers into normal number values (using text2num-server)
    private fun parseNumber(message : String) {
        val url = "http://$wsip:4200/?message=$message"

        var postData = JSONObject();
        postData.put("message", message)

        val jsonRequest = JsonObjectRequest(
            Request.Method.GET, url, postData,
            { response ->
                val responseMessage = response["message"].toString()
                sendMessage(responseMessage)
            },
            {
                tts.playErrorMessage()
                showToast(this, "Keine Verbindung zum text2num-Server")
            })

        requestQueue.add(jsonRequest)
    }

    fun playRecognitionChime() {
        val mp = MediaPlayer.create(applicationContext, resources.getIdentifier("voice_recognition_chime", "raw", packageName))
        mp.start()
    }

}
