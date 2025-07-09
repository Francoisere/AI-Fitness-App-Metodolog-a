/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.graphics.PointF
import kotlin.math.acos
import kotlin.math.sqrt
import android.speech.tts.TextToSpeech
import android.content.Intent

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {



    private var currentExercise = "PUSH_UP"
    private var exerciseCounter = 0
    private var isExerciseDown = false
    private var lastExerciseState = "UP"

    private var results: PoseLandmarkerResult? = null
    private var pointPaint = Paint()
    private var linePaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var lastFeedbackCount = 0
    private var currentFeedback = ""
    private var feedbackColor = Color.WHITE
    private var feedbackStartTime = 0L
    private val feedbackDuration = 3000L // 3 segundos

    private var recentAngles = mutableListOf<Map<String, Float>>()
    private val maxRecentAngles = 9

    // Variables para feedback suave (sin parpadeo)

    private var lastTechnicalMessage = ""
    private var lastTechnicalColor = Color.WHITE
    private var lastPhase = "ARRIBA"
    private var lastAnglesText = mapOf<String, String>()

    //TTS
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var lastSpokenMessage = ""




    fun exportUserPerformanceToFile() {
        val filename = "reporte_entrenamiento_${System.currentTimeMillis()}.txt"
        val fileContents = buildString {
            append("=== REPORTE DE ENTRENAMIENTO ===\n")
            append("Ejercicio: ${getExerciseName()}\n")
            append("Repeticiones: $exerciseCounter\n")
            append("Fecha: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n\n")

            append("Feedback técnico:\n")
            append("- Último feedback inmediato: $lastTechnicalMessage\n")
            append("- Último feedback general: $currentFeedback\n")
            append("Promedio de ángulos (últimos ${recentAngles.size}):\n")

            val avgAngles = calculateAverageAngles()
            for ((key, value) in avgAngles) {
                append(" - $key: ${"%.1f".format(value)}°\n")
            }
        }

        try {
            val file = java.io.File(context?.getExternalFilesDir(null), filename)
            file.writeText(fileContents)
            android.util.Log.d("OverlayView", "Archivo exportado a: ${file.absolutePath}")
            speak("Reporte guardado exitosamente.")

            //mostrar pantalla reporte
            val intent = Intent(context, ReportActivity::class.java)
            intent.putExtra("reporte_texto", fileContents)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            context?.startActivity(intent)

        } catch (e: Exception) {
            android.util.Log.e("OverlayView", "Error al guardar archivo: ${e.message}")
            speak("Error al guardar el reporte.")

        }
    }



    private fun calculateAverageAngles(): Map<String, Float> {
        if (recentAngles.isEmpty()) return emptyMap()

        val angleKeys = recentAngles.first().keys
        val averages = mutableMapOf<String, Float>()

        for (key in angleKeys) {
            val sum = recentAngles.mapNotNull { it[key] }.sum()
            val avg = sum / recentAngles.size
            averages[key] = avg
        }
        return averages
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tts?.stop()
        tts?.shutdown()
    }


    private var correctPaint = Paint().apply {
        color = Color.GREEN
        textSize = 50f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private fun speak(text: String) {
        if (isTtsInitialized && text != lastSpokenMessage) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            lastSpokenMessage = text
        }
    }

    private fun evaluateExercise(angles: Map<String, Float>): ExerciseFeedback {
        android.util.Log.d("OverlayView", "Evaluando ejercicio: $currentExercise")

        return when (currentExercise) {
            "PUSH_UP" -> {
                android.util.Log.d("OverlayView", "Evaluando PUSH_UP")
                evaluatePushUp(angles)
            }
            "SQUAT" -> {
                android.util.Log.d("OverlayView", "Evaluando SQUAT")
                evaluateSquat(angles)
            }
            "BICEP_CURL" -> {
                android.util.Log.d("OverlayView", "Evaluando BICEP_CURL")
                evaluateBicepCurl(angles)
            }
            "PLANK" -> {
                android.util.Log.d("OverlayView", "Evaluando PLANK")
                evaluatePlank(angles)
            }
            else -> {
                android.util.Log.d("OverlayView", "Ejercicio no reconocido: $currentExercise")
                ExerciseFeedback("Ejercicio no reconocido: $currentExercise", Color.WHITE)
            }
        }
    }

    private fun evaluatePushUp(angles: Map<String, Float>): ExerciseFeedback {
        val rightElbow = angles["rightElbow"] ?: 180f
        val leftElbow = angles["leftElbow"] ?: 180f
        val rightShoulder = angles["rightShoulder"] ?: 180f
        val leftShoulder = angles["leftShoulder"] ?: 180f
        val avgElbow = (rightElbow + leftElbow) / 2

        // Guardar ángulos para análisis
        storeAnglesForAnalysis(angles)

        // Contar repeticiones
        countReps(avgElbow, 110f, 150f)

        // Feedback inmediato solo para errores críticos
        return when {
            avgElbow > 160f -> ExerciseFeedback("Baja más los codos", Color.RED)
            avgElbow < 50f -> ExerciseFeedback("No bajes tanto", Color.RED)
            abs(rightElbow - leftElbow) > 25f -> ExerciseFeedback("Corrige asimetría", Color.YELLOW)
            abs(rightShoulder - leftShoulder) > 20f -> ExerciseFeedback("Alinea hombros", Color.YELLOW)
            else -> ExerciseFeedback("", Color.WHITE)
        }
    }


    private fun evaluateSquat(angles: Map<String, Float>): ExerciseFeedback {
        val rightKnee = angles["rightKnee"] ?: 180f
        val leftKnee = angles["leftKnee"] ?: 180f
        val rightHip = angles["rightHip"] ?: 180f
        val leftHip = angles["leftHip"] ?: 180f
        val avgKnee = (rightKnee + leftKnee) / 2

        // Guardar ángulos para análisis
        storeAnglesForAnalysis(angles)

        // Contar repeticiones
        countReps(avgKnee, 120f, 150f)

        // Feedback inmediato solo para errores críticos
        return when {
            avgKnee > 170f -> ExerciseFeedback("Baja más profundo", Color.RED)
            avgKnee < 70f -> ExerciseFeedback("No bajes tanto", Color.RED)
            abs(rightKnee - leftKnee) > 20f -> ExerciseFeedback("Equilibra las piernas", Color.YELLOW)
            abs(rightHip - leftHip) > 15f -> ExerciseFeedback("Corrige postura cadera", Color.YELLOW)
            else -> ExerciseFeedback("", Color.WHITE)
        }
    }

    private fun evaluateBicepCurl(angles: Map<String, Float>): ExerciseFeedback {
        val rightElbow = angles["rightElbow"] ?: 180f
        val leftElbow = angles["leftElbow"] ?: 180f
        val rightShoulder = angles["rightShoulder"] ?: 180f
        val leftShoulder = angles["leftShoulder"] ?: 180f
        val avgElbow = (rightElbow + leftElbow) / 2

        // Guardar ángulos para análisis
        storeAnglesForAnalysis(angles)

        // Contar repeticiones
        countReps(avgElbow, 60f, 140f)

        // Feedback inmediato solo para errores críticos
        return when {
            avgElbow > 160f -> ExerciseFeedback("Flexiona más el brazo", Color.RED)
            avgElbow < 30f -> ExerciseFeedback("Controla la bajada", Color.RED)
            abs(rightElbow - leftElbow) > 20f -> ExerciseFeedback("Sincroniza ambos brazos", Color.YELLOW)
            abs(rightShoulder - leftShoulder) > 15f -> ExerciseFeedback("Estabiliza hombros", Color.YELLOW)
            else -> ExerciseFeedback("", Color.WHITE)
        }
    }

    private fun evaluatePlank(angles: Map<String, Float>): ExerciseFeedback {
        val rightElbow = angles["rightElbow"] ?: 180f
        val leftElbow = angles["leftElbow"] ?: 180f
        val rightShoulder = angles["rightShoulder"] ?: 180f
        val leftShoulder = angles["leftShoulder"] ?: 180f

        // Guardar ángulos para análisis
        storeAnglesForAnalysis(angles)

        val avgElbow = (rightElbow + leftElbow) / 2
        val avgShoulder = (rightShoulder + leftShoulder) / 2

        // Para plancha, incrementar contador de tiempo
        if (avgElbow in 80f..110f && avgShoulder in 160f..180f) {
            exerciseCounter++
            if (exerciseCounter % 90 == 0) { // Cada 3 segundos aprox
                checkForTechnicalFeedback()
            }
        }

        return when {
            avgElbow < 70f -> ExerciseFeedback("Sube las caderas", Color.RED)
            avgElbow > 120f -> ExerciseFeedback("Baja las caderas", Color.RED)
            avgShoulder < 150f -> ExerciseFeedback("Endereza la espalda", Color.RED)
            abs(rightElbow - leftElbow) > 15f -> ExerciseFeedback("Equilibra los codos", Color.YELLOW)
            else -> ExerciseFeedback("", Color.WHITE)
        }
    }

    private fun storeAnglesForAnalysis(angles: Map<String, Float>) {
        recentAngles.add(angles.toMap())
        if (recentAngles.size > maxRecentAngles) {
            recentAngles.removeAt(0)
        }
    }


    private fun countReps(angle: Float, downThreshold: Float, upThreshold: Float) {
        when {
            angle < downThreshold && lastExerciseState == "UP" -> {
                isExerciseDown = true
                lastExerciseState = "DOWN"
            }
            angle > upThreshold && lastExerciseState == "DOWN" && isExerciseDown -> {
                exerciseCounter++
                isExerciseDown = false
                lastExerciseState = "UP"

                // Verificar feedback técnico cada 3 repeticiones
                checkForTechnicalFeedback()
            }
        }
    }

    private fun checkForTechnicalFeedback() {
        if (exerciseCounter > 0 && exerciseCounter % 3 == 0 && exerciseCounter != lastFeedbackCount) {
            lastFeedbackCount = exerciseCounter
            analyzeTechnicalPerformance()
            feedbackStartTime = System.currentTimeMillis()
        }
    }

    private fun analyzeTechnicalPerformance() {
        if (recentAngles.isEmpty()) return

        when (currentExercise) {
            "PUSH_UP" -> analyzePushUpTechnique()
            "SQUAT" -> analyzeSquatTechnique()
            "BICEP_CURL" -> analyzeBicepCurlTechnique()
            "PLANK" -> analyzePlankTechnique()
        }
    }

    private fun analyzePushUpTechnique() {
        val avgRightElbow = recentAngles.map { it["rightElbow"] ?: 180f }.average().toFloat()
        val avgLeftElbow = recentAngles.map { it["leftElbow"] ?: 180f }.average().toFloat()
        val avgRightShoulder = recentAngles.map { it["rightShoulder"] ?: 180f }.average().toFloat()
        val avgLeftShoulder = recentAngles.map { it["leftShoulder"] ?: 180f }.average().toFloat()

        val elbowAsymmetry = abs(avgRightElbow - avgLeftElbow)
        val shoulderAsymmetry = abs(avgRightShoulder - avgLeftShoulder)
        val avgElbow = (avgRightElbow + avgLeftElbow) / 2

        currentFeedback = when {
            avgElbow > 130f -> "CORRECCIÓN: Baja más los codos (objetivo: 90°)"
            avgElbow < 70f -> "CORRECCIÓN: No bajes tanto (objetivo: 90°)"
            elbowAsymmetry > 15f -> "CORRECCIÓN: Mantén simetría en los brazos"
            shoulderAsymmetry > 12f -> "CORRECCIÓN: Alinea los hombros"
            else -> "TÉCNICA CORRECTA: Mantén esta forma"
        }

        feedbackColor = if (currentFeedback.contains("CORRECTA")) Color.GREEN else Color.rgb(255,165,0)
        speak(currentFeedback)
    }

    private fun analyzeSquatTechnique() {
        val avgRightKnee = recentAngles.map { it["rightKnee"] ?: 180f }.average().toFloat()
        val avgLeftKnee = recentAngles.map { it["leftKnee"] ?: 180f }.average().toFloat()
        val avgRightHip = recentAngles.map { it["rightHip"] ?: 180f }.average().toFloat()
        val avgLeftHip = recentAngles.map { it["leftHip"] ?: 180f }.average().toFloat()

        val kneeAsymmetry = abs(avgRightKnee - avgLeftKnee)
        val hipAsymmetry = abs(avgRightHip - avgLeftHip)
        val avgKnee = (avgRightKnee + avgLeftKnee) / 2

        currentFeedback = when {
            avgKnee > 140f -> "CORRECCIÓN: Baja más profundo (objetivo: 90°)"
            avgKnee < 80f -> "CORRECCIÓN: No bajes tanto (objetivo: 90°)"
            kneeAsymmetry > 12f -> "CORRECCIÓN: Equilibra ambas piernas"
            hipAsymmetry > 10f -> "CORRECCIÓN: Mantén cadera alineada"
            else -> "TÉCNICA CORRECTA: Profundidad ideal"
        }

        feedbackColor = if (currentFeedback.contains("CORRECTA")) Color.GREEN else Color.rgb(255,165,0)
        speak(currentFeedback)
    }

    private fun analyzeBicepCurlTechnique() {
        val avgRightElbow = recentAngles.map { it["rightElbow"] ?: 180f }.average().toFloat()
        val avgLeftElbow = recentAngles.map { it["leftElbow"] ?: 180f }.average().toFloat()
        val avgRightShoulder = recentAngles.map { it["rightShoulder"] ?: 180f }.average().toFloat()
        val avgLeftShoulder = recentAngles.map { it["leftShoulder"] ?: 180f }.average().toFloat()

        val elbowAsymmetry = abs(avgRightElbow - avgLeftElbow)
        val shoulderMovement = abs(avgRightShoulder - avgLeftShoulder)
        val avgElbow = (avgRightElbow + avgLeftElbow) / 2

        currentFeedback = when {
            avgElbow > 120f -> "CORRECCIÓN: Flexiona más los brazos"
            avgElbow < 40f -> "CORRECCIÓN: Controla el rango de movimiento"
            elbowAsymmetry > 15f -> "CORRECCIÓN: Sincroniza ambos brazos"
            shoulderMovement > 12f -> "CORRECCIÓN: Estabiliza los hombros"
            else -> "TÉCNICA CORRECTA: Movimiento controlado"
        }

        feedbackColor = if (currentFeedback.contains("CORRECTA")) Color.GREEN else Color.rgb(255,165,0)
        speak(currentFeedback)
    }

    // NUEVA FUNCIÓN: Analizar técnica de plancha
    private fun analyzePlankTechnique() {
        val avgRightElbow = recentAngles.map { it["rightElbow"] ?: 180f }.average().toFloat()
        val avgLeftElbow = recentAngles.map { it["leftElbow"] ?: 180f }.average().toFloat()
        val avgRightShoulder = recentAngles.map { it["rightShoulder"] ?: 180f }.average().toFloat()
        val avgLeftShoulder = recentAngles.map { it["leftShoulder"] ?: 180f }.average().toFloat()

        val elbowAsymmetry = abs(avgRightElbow - avgLeftElbow)
        val shoulderAsymmetry = abs(avgRightShoulder - avgLeftShoulder)
        val avgElbow = (avgRightElbow + avgLeftElbow) / 2
        val avgShoulder = (avgRightShoulder + avgLeftShoulder) / 2

        currentFeedback = when {
            avgElbow < 80f -> "CORRECCIÓN: Sube las caderas"
            avgElbow > 110f -> "CORRECCIÓN: Baja las caderas"
            avgShoulder < 160f -> "CORRECCIÓN: Endereza la espalda"
            elbowAsymmetry > 10f -> "CORRECCIÓN: Equilibra los codos"
            shoulderAsymmetry > 8f -> "CORRECCIÓN: Alinea los hombros"
            else -> "TÉCNICA CORRECTA: Plancha estable"
        }

        feedbackColor = if (currentFeedback.contains("CORRECTA")) Color.GREEN else Color.rgb(255,165,0)
        speak(currentFeedback)
    }

    private fun resetExerciseCounters() {
        exerciseCounter = 0
        isExerciseDown = false
        lastExerciseState = "UP"
        lastFeedbackCount = 0
        currentFeedback = ""
        feedbackStartTime = 0L
        recentAngles.clear()
    }



    private fun getExerciseName(): String {
        return when (currentExercise) {
            "PUSH_UP" -> "Push-ups"
            "SQUAT" -> "Sentadillas"
            "BICEP_CURL" -> "Curl de Bíceps"
            "PLANK" -> "Plancha"
            else -> "Ejercicio"
        }
    }

    fun setCurrentExercise(exercise: String) {
        android.util.Log.d("OverlayView", "Cambiando ejercicio de $currentExercise a $exercise")
        currentExercise = exercise
        exportUserPerformanceToFile()
        resetExerciseCounters()

        // Forzar actualización de la vista
        invalidate()
    }

    init {
        initPaints()
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale("es", "ES") // Español

                val maleVoice = tts?.voices?.find { it.name.contains("male") }
                maleVoice?.let { tts?.voice = it }

                isTtsInitialized = true
            }
        }
    }

    fun clear() {
        results = null
        pointPaint.reset()
        linePaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL
    }

    // Agregar después de initPaints()
    private fun calculateAngle(point1: PointF, point2: PointF, point3: PointF): Float {
        val vector1 = PointF(point1.x - point2.x, point1.y - point2.y)
        val vector2 = PointF(point3.x - point2.x, point3.y - point2.y)

        val dotProduct = vector1.x * vector2.x + vector1.y * vector2.y
        val magnitude1 = sqrt(vector1.x * vector1.x + vector1.y * vector1.y)
        val magnitude2 = sqrt(vector2.x * vector2.x + vector2.y * vector2.y)

        if (magnitude1 == 0f || magnitude2 == 0f) return 0f

        val angle = acos((dotProduct / (magnitude1 * magnitude2)).coerceIn(-1f, 1f))
        return Math.toDegrees(angle.toDouble()).toFloat()
    }


    // Paint para el texto de ángulos
    private var textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
        isAntiAlias = true
    }



    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Info básica siempre visible
        canvas.drawText("Ejercicio: ${getExerciseName()}", 50f, 50f, textPaint)
        canvas.drawText("${getExerciseName()}: $exerciseCounter", 50f, 100f, correctPaint)

        results?.let { poseLandmarkerResult ->
            for (landmark in poseLandmarkerResult.landmarks()) {

                // Dibujar puntos (solo cuerpo - más rápido)
                for (i in 11 until landmark.size) {
                    val normalizedLandmark = landmark[i]
                    canvas.drawPoint(
                        normalizedLandmark.x() * imageWidth * scaleFactor,
                        normalizedLandmark.y() * imageHeight * scaleFactor,
                        pointPaint
                    )
                }

                // Dibujar líneas (solo cuerpo - más rápido)
                PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                    val startIndex = connection!!.start()
                    val endIndex = connection.end()

                    if (startIndex >= 11 && endIndex >= 11) {
                        canvas.drawLine(
                            poseLandmarkerResult.landmarks().get(0).get(startIndex).x() * imageWidth * scaleFactor,
                            poseLandmarkerResult.landmarks().get(0).get(startIndex).y() * imageHeight * scaleFactor,
                            poseLandmarkerResult.landmarks().get(0).get(endIndex).x() * imageWidth * scaleFactor,
                            poseLandmarkerResult.landmarks().get(0).get(endIndex).y() * imageHeight * scaleFactor,
                            linePaint
                        )
                    }


                }

                if (System.currentTimeMillis() % 200 < 50 && landmark.size >= 28) {
                    evaluateAndUpdateFeedback(landmark)
                }

                // VISUALIZACIÓN - Siempre fluida (60fps)
                displayCurrentFeedback(canvas, landmark)
            }
        }
    }

    private fun evaluateAndUpdateFeedback(landmark: Any) {
        val pose = landmark as List<*>

        if (pose.size <28) return

        fun getX(index: Int): Float {
            val point = pose[index]
            return (point?.javaClass?.getMethod("x")?.invoke(point) as? Float) ?: 0f
        }

        fun getY(index: Int): Float {
            val point = pose[index]
            return (point?.javaClass?.getMethod("y")?.invoke(point) as? Float) ?: 0f
        }

        // Calcular puntos y ángulos
        val leftShoulder = PointF(getX(11) * imageWidth * scaleFactor, getY(11) * imageHeight * scaleFactor)
        val rightShoulder = PointF(getX(12) * imageWidth * scaleFactor, getY(12) * imageHeight * scaleFactor)
        val leftElbow = PointF(getX(13) * imageWidth * scaleFactor, getY(13) * imageHeight * scaleFactor)
        val rightElbow = PointF(getX(14) * imageWidth * scaleFactor, getY(14) * imageHeight * scaleFactor)
        val leftWrist = PointF(getX(15) * imageWidth * scaleFactor, getY(15) * imageHeight * scaleFactor)
        val rightWrist = PointF(getX(16) * imageWidth * scaleFactor, getY(16) * imageHeight * scaleFactor)

        val leftHip = PointF(getX(23) * imageWidth * scaleFactor,  getY(23)* imageHeight * scaleFactor)
        val rightHip = PointF(getX(24) * imageWidth * scaleFactor, getY(24) * imageHeight * scaleFactor)
        val leftKnee = PointF(getX(25) * imageWidth * scaleFactor, getY(25) * imageHeight * scaleFactor)
        val rightKnee = PointF(getX(26) * imageWidth * scaleFactor, getY(26) * imageHeight * scaleFactor)
        val leftAnkle = PointF(getX(27) * imageWidth * scaleFactor, getY(27) * imageHeight * scaleFactor)
        val rightAnkle = PointF(getX(28) * imageWidth * scaleFactor, getY(28) * imageHeight * scaleFactor)

        // Calcular ángulos
        val leftElbowAngle = calculateAngle(leftShoulder, leftElbow, leftWrist)
        val rightElbowAngle = calculateAngle(rightShoulder, rightElbow, rightWrist)
        val leftKneeAngle = calculateAngle(leftHip, leftKnee, leftAnkle)
        val rightKneeAngle = calculateAngle(rightHip, rightKnee, rightAnkle)
        val leftShoulderAngle = calculateAngle(leftElbow, leftShoulder, leftHip)
        val rightShoulderAngle = calculateAngle(rightElbow, rightShoulder, rightHip)
        val leftHipAngle = calculateAngle(leftShoulder, leftHip, leftKnee)
        val rightHipAngle = calculateAngle(rightShoulder, rightHip, rightKnee)

        val angles = mapOf(
            "leftElbow" to leftElbowAngle,
            "rightElbow" to rightElbowAngle,
            "leftShoulder" to leftShoulderAngle,
            "rightShoulder" to rightShoulderAngle,
            "leftKnee" to leftKneeAngle,
            "rightKnee" to rightKneeAngle,
            "leftHip" to leftHipAngle,
            "rightHip" to rightHipAngle
        )

        // Actualizar feedback técnico inmediato
        val feedback = evaluateExercise(angles)
        if (feedback.message.isNotEmpty() && feedback.message != lastTechnicalMessage) {
            speak(feedback.message) // 🔊 hablar errores
        }
        lastTechnicalMessage = feedback.message
        lastTechnicalColor = feedback.color

        // Actualizar textos de ángulos
        lastAnglesText = when (currentExercise) {
            "SQUAT" -> mapOf(
                "left" to "L: ${leftKneeAngle.toInt()}°",
                "right" to "R: ${rightKneeAngle.toInt()}°",
                "debug" to "Promedio rodillas: ${((leftKneeAngle + rightKneeAngle) / 2).toInt()}°"
            )
            "PUSH_UP", "BICEP_CURL" -> mapOf(
                "left" to "L: ${leftElbowAngle.toInt()}°",
                "right" to "R: ${rightElbowAngle.toInt()}°",
                "debug" to "Promedio codos: ${((leftElbowAngle + rightElbowAngle) / 2).toInt()}°"
            )
            else -> mapOf()
        }

        // Actualizar fase
        lastPhase = when (currentExercise) {
            "SQUAT" -> {
                val avgKnee = (leftKneeAngle + rightKneeAngle) / 2
                when {
                    avgKnee > 150f -> "ARRIBA"
                    avgKnee < 120f -> "ABAJO"
                    else -> "MEDIO"
                }
            }
            else -> {
                val avgElbow = (leftElbowAngle + rightElbowAngle) / 2
                when {
                    avgElbow > 150f -> "ARRIBA"
                    avgElbow < 100f -> "ABAJO"
                    else -> "MEDIO"
                }
            }
        }
    }


private fun displayCurrentFeedback(canvas: Canvas, landmark: Any) {
    val pose = landmark as List<*>

    // Mostrar feedback técnico inmediato (sin parpadeo)
    if (lastTechnicalMessage.isNotEmpty()) {
        val technicalPaint = Paint().apply {
            color = lastTechnicalColor
            textSize = 32f
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawText(lastTechnicalMessage, width - 250f, 100f, technicalPaint)
    }

    // Mostrar feedback cada 3 repeticiones (sin parpadeo)
    val currentTime = System.currentTimeMillis()
    if (currentFeedback.isNotEmpty() &&
        currentTime - feedbackStartTime < feedbackDuration) {

        val feedbackPaint = Paint().apply {
            color = feedbackColor
            textSize = 45f
            style = Paint.Style.FILL
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        val textWidth = feedbackPaint.measureText(currentFeedback)
        canvas.drawText(
            currentFeedback,
            (width - textWidth) / 2f,
            height / 2f - 80,
            feedbackPaint
        )
    }

    // Mostrar ángulos actualizados (sin parpadeo)
    if (pose.size >= 28) {

        fun getX(index: Int): Float {
            val point = pose[index]
            return (point?.javaClass?.getMethod("x")?.invoke(point) as? Float) ?: 0f
        }

        fun getY(index: Int): Float {
            val point = pose[index]
            return (point?.javaClass?.getMethod("y")?.invoke(point) as? Float) ?: 0f
        }


        when (currentExercise) {
            "SQUAT" -> {
                val leftKnee = PointF(getX(25) * imageWidth * scaleFactor, getY(25) * imageHeight * scaleFactor)
                val rightKnee = PointF(getX(26) * imageWidth * scaleFactor, getY(26) * imageHeight * scaleFactor)

                lastAnglesText["left"]?.let {
                    canvas.drawText(it, leftKnee.x - 60, leftKnee.y, textPaint)
                }
                lastAnglesText["right"]?.let {
                    canvas.drawText(it, rightKnee.x + 20, rightKnee.y, textPaint)
                }
                lastAnglesText["debug"]?.let {
                    canvas.drawText(it, 50f, 200f, textPaint)
                }
            }
            "PUSH_UP", "BICEP_CURL" -> {
                val leftElbow = PointF( getX(13)* imageWidth * scaleFactor, getY(13) * imageHeight * scaleFactor)
                val rightElbow = PointF(getX(14) * imageWidth * scaleFactor, getY(14) * imageHeight * scaleFactor)

                lastAnglesText["left"]?.let {
                    canvas.drawText(it, leftElbow.x - 80, leftElbow.y - 20, textPaint)
                }
                lastAnglesText["right"]?.let {
                    canvas.drawText(it, rightElbow.x + 20, rightElbow.y - 20, textPaint)
                }
            }
        }
    }

    // Mostrar fase (sin parpadeo)
    canvas.drawText("Fase: $lastPhase", 50f, 150f, textPaint)
    }






    fun setResults(
        poseLandmarkerResults: PoseLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = poseLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // PreviewView is in FILL_START mode. So we need to scale up the
                // landmarks to match with the size that the captured images will be
                // displayed.
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 12F
    }

    data class ExerciseFeedback(val message: String, val color: Int)
}