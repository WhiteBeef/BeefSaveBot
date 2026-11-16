package ru.whitebeef.beefsavebot.service;

import java.io.File;

@FunctionalInterface
public interface Downloader {

  File download() throws Exception;
}
