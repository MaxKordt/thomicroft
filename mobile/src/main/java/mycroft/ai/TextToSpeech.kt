package mycroft.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import kotlinx.android.synthetic.main.content_main.*
import mycroft.ai.shared.utilities.GuiUtilities.showToast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.util.*

class TextToSpeech {
    private var serverIp : String
    private var context : Context
    private var queue : RequestQueue
    private var url : String
    private var port : String
    private var filePath : File
    private var mediaPlayer : MediaPlayer = MediaPlayer()
    private var files : Queue<File> = LinkedList<File>()
    private var currentOutput = 0
    private var minTimeTillFiller : Double = 5000000000.0
    private var lastFiller = System.nanoTime()
    private var currentFiller = System.nanoTime()
    public var lastUtterance : String = ""

    constructor(context : Context, serverIp : String, port : String = "59125") {
        this.serverIp = serverIp
        this.context = context
        this.port = port
        queue = Volley.newRequestQueue(context)
        url = "http://$serverIp:$port"
        filePath = context.filesDir
    }
/*
    fun sendTTSRequest(input_text : String) {
        /*
        MaryTTS:
        var mUrl = "$url/process?INPUT_TEXT=$input_text&INPUT_TYPE=TEXT&OUTPUT_TYPE=AUDIO&AUDIO=WAVE_FILE&LOCALE=de&VOICE=bits3-hsmm"
        Larynx with Eva-Voice
        var mUrl = "$url/api/tts?text=$input_text&voice=de-de/eva_k-glow_tts&vocoder=hifi_gan/vctk_medium&denoiserStrength=0.005&noiseScale=0.333&lengthScale=1"
        Larynx with Karlsson-Voice
         */
        //var mUrl = "$url/api/tts?text=$input_text&voice=de-de/karlsson-glow_tts&vocoder=hifi_gan/vctk_small&denoiserStrength=0.01&noiseScale=0.333&lengthScale=1"
        var mUrl = "$url/api/tts?text=Es ist im Moment Überwiegend bewölkt bei dreizehn Grad celsius.&voice=de-de/karlsson-glow_tts&vocoder=hifi_gan/vctk_small&denoiserStrength=0.01&noiseScale=0.333&lengthScale=1"
        var hashMap: HashMap<String, String> = HashMap()
        var request = InputStreamVolleyRequest(
            context, Request.Method.GET, mUrl,
            Response.Listener<ByteArray>() { response ->
                writeWavFile(response)
            },
            Response.ErrorListener { error ->
                showToast(context, error.toString())
                playErrorMessage()
            },
            hashMap)
        request.retryPolicy = DefaultRetryPolicy(20000, 0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
        queue.add(request)
    }
    */

    fun sendTTSRequest(input_text : String) {
        /*
        MaryTTS:
        var mUrl = "$url/process?INPUT_TEXT=$input_text&INPUT_TYPE=TEXT&OUTPUT_TYPE=AUDIO&AUDIO=WAVE_FILE&LOCALE=de&VOICE=bits3-hsmm"
        Larynx with Eva-Voice
        var mUrl = "$url/api/tts?text=$input_text&voice=de-de/eva_k-glow_tts&vocoder=hifi_gan/vctk_medium&denoiserStrength=0.005&noiseScale=0.333&lengthScale=1"
        Larynx with Karlsson-Voice
         */

        if (input_text.equals("Ich wiederhole")) {


        }
        else {

            var mUrl = "$url/api/tts?option=raw-stream&text=$input_text&voice=de-de/karlsson-glow_tts&vocoder=hifi_gan/vctk_small&denoiserStrength=0.01&noiseScale=0.333&lengthScale=1"
            //var mUrl = "$url/api/tts?text=$input_text"//&voice=de-de/eva_k-glow_tts&vocoder=hifi_gan/vctk_medium&denoiserStrength=0.005&noiseScale=0.333&lengthScale=1"
            //var mUrl = "$url/api/tts?text=$input_text&voice=en-us/blizzard_fls-glow_tts&vocoder=hifi_gan/vctk_medium&denoiserStrength=0.01&noiseScale=0.333&lengthScale=1"
            var hashMap: HashMap<String, String> = HashMap()
            var request = InputStreamVolleyRequest(
                    context, Request.Method.GET, mUrl,
                    Response.Listener<ByteArray>() { response ->
                        writeWavFile(response)
                    },
                    Response.ErrorListener { error ->
                        showToast(context, error.toString())
                        playErrorMessage()
                    },
                    hashMap)
            request.retryPolicy = DefaultRetryPolicy(20000, 0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
            queue.add(request)

            var rand = Random().nextInt(4)
            if (input_text.length > 50) {

                currentFiller = System.nanoTime()
                if (currentFiller - lastFiller > minTimeTillFiller) playFiller(rand, input_text)
                lastFiller = currentFiller
            }
        }
    }

    /*fun sendTTSRequest(input_text : String) {

        val tts = android.speech.tts.TextToSpeech(context, android.speech.tts.TextToSpeech.OnInitListener{

            @Override
            fun onInit(status : Int) {


            }
        })
        Log.i("Test", "Hallo")
        tts.setLanguage(Locale.GERMANY);
        tts.speak(input_text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null)
    }*/

    private fun playFromByte(data : ByteArray) {

        val tempMp3 = File.createTempFile("temp", "mp3", filePath)
        tempMp3.deleteOnExit()
        val fos = FileOutputStream(tempMp3)
        fos.write(data)
        fos.close()

        mediaPlayer.reset() //avoid running out of resources

        val fis = FileInputStream(tempMp3);
        mediaPlayer.setDataSource(fis.fd)
        mediaPlayer.prepare()
        mediaPlayer.start()
        /*mediaPlayer.setOnPreparedListener(MediaPlayer.OnPreparedListener {

            @Override
            fun onPrepared(player : MediaPlayer) {

                player.start()
            }
        })*/


        /*mediaPlayer.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
        mediaPlayer.setDataSource(mUrl)
        mediaPlayer.prepareAsync()
        mediaPlayer.start()*/
    }

    //keep last files and play them if repeat wanted or get input and try to send it to mycroft again
    private fun writeWavFile(data : ByteArray) {
        val file = File(filePath, "output$currentOutput.wav")
        currentOutput = (currentOutput + 1).rem(10)
        FileOutputStream(file).use {
            it.write(data)
        }
        files.add(file)
        play()
    }

    private fun play() {
        if(!mediaPlayer.isPlaying && !files.isEmpty()) {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(files.remove().path)
            mediaPlayer.prepare()
            mediaPlayer.start()
        }

        // when WAV is received while audio is still playing
        if (mediaPlayer.isPlaying && !files.isEmpty()) {
            mediaPlayer.setOnCompletionListener { play() }
        }
    }

    // play message when server is not responding
    fun playErrorMessage() {
        mediaPlayer.reset()
        val filename = "android.resource://" + context.packageName + "/raw/error_no_connection"
        mediaPlayer.setDataSource(context, Uri.parse(filename))
        mediaPlayer.prepare()
        mediaPlayer.start()
    }

    private fun playFiller(fillerNumber : Int, text : String) {

        mediaPlayer.reset()
        var filename = "android.resource://" + context.packageName + "/raw/inordnung"
        if (fillerNumber == 0) {

            filename = "android.resource://" + context.packageName + "/raw/inordnung"
        }
        if (fillerNumber == 1) {

            filename = "android.resource://" + context.packageName + "/raw/einenmoment"
        }
        if (fillerNumber == 2) {

            filename = "android.resource://" + context.packageName + "/raw/ok"
        }
        if (fillerNumber == 3) {

            filename = "android.resource://" + context.packageName + "/raw/verstanden"
        }
        if (fillerNumber == 4) {

            filename = "android.resource://" + context.packageName + "/raw/wetter"
        }

        if (text.length > 200) filename = "android.resource://" + context.packageName + "/raw/einenmoment"

        mediaPlayer.setDataSource(context, Uri.parse(filename))
        mediaPlayer.prepare()
        mediaPlayer.start()
    }

}