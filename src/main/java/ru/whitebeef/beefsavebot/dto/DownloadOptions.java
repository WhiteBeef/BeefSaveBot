package ru.whitebeef.beefsavebot.dto;

import ru.whitebeef.beefsavebot.model.Quality;

/**
 * @param quality        желаемое качество
 * @param audioOnly      нужен только звук (видео можно не скачивать, если площадка это позволяет)
 * @param maxSourceBytes максимальный размер скачиваемого исходника
 */
public record DownloadOptions(Quality quality, boolean audioOnly, long maxSourceBytes) {

}
