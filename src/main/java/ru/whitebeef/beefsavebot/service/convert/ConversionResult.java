package ru.whitebeef.beefsavebot.service.convert;

import java.nio.file.Path;

/**
 * @param file        готовый файл для отправки
 * @param description что было сделано (для подписи и лога)
 */
public record ConversionResult(Path file, String description) {

}
