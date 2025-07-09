package com.google.mediapipe.examples.poselandmarker

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ReportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        val reportText = intent.getStringExtra("reporte_texto") ?: "No se pudo cargar el reporte"
        val textView = findViewById<TextView>(R.id.textViewReport)
        textView.text = reportText
    }
}

