package ru.whitebeef.beefsavebot.service;

import ru.whitebeef.beefsavebot.service.convert.ConversionResult;

@FunctionalInterface
public interface Conversion {

  ConversionResult run() throws Exception;
}
