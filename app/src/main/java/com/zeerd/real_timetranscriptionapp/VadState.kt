package com.zeerd.real_timetranscriptionapp

enum class VadState {
    IDLE,       // Waiting for speech onset
    RECORDING   // Speech detected, accumulating audio segment
}
