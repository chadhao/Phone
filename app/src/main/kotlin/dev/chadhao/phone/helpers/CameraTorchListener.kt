package dev.chadhao.phone.helpers

interface CameraTorchListener {
    fun onTorchEnabled(isEnabled:Boolean)

    fun onTorchUnavailable()
}
