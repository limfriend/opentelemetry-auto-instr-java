/*
 * Copyright 2020, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.opentelemetry.auto.bootstrap;

import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Dependencies of the agent somtimes call Logger.getLogger This can have the effect of initializing
 * the global logger incompatibly with the user's app.
 *
 * <p>Shadow rewrites will redirect those calls to this class, which will return a safe logger.
 */
public class PatchLogger {
  private static final PatchLogger SAFE_LOGGER = new PatchLogger("agentSafeLogger", "bundle");

  public static PatchLogger getLogger(final String name) {
    return SAFE_LOGGER;
  }

  public static PatchLogger getLogger(final String name, final String resourceBundleName) {
    return SAFE_LOGGER;
  }

  public static PatchLogger getAnonymousLogger() {
    return SAFE_LOGGER;
  }

  public static PatchLogger getAnonymousLogger(final String resourceBundleName) {
    return SAFE_LOGGER;
  }

  protected PatchLogger(final String name, final String resourceBundleName) {
    // super(name, resourceBundleName);
  }

  // providing a bunch of empty log methods

  public void log(final LogRecord record) {}

  public void log(final Level level, final String msg) {}

  public void log(final Level level, final String msg, final Object param1) {}

  public void log(final Level level, final String msg, final Object[] params) {}

  public void log(final Level level, final String msg, final Throwable thrown) {}

  public void logp(
      final Level level, final String sourceClass, final String sourceMethod, final String msg) {}

  public void logp(
      final Level level,
      final String sourceClass,
      final String sourceMethod,
      final String msg,
      final Object param1) {}

  public void logp(
      final Level level,
      final String sourceClass,
      final String sourceMethod,
      final String msg,
      final Object[] params) {}

  public void logp(
      final Level level,
      final String sourceClass,
      final String sourceMethod,
      final String msg,
      final Throwable thrown) {}

  public void logrb(
      final Level level,
      final String sourceClass,
      final String sourceMethod,
      final String bundleName,
      final String msg) {}

  public void logrb(
      final Level level,
      final String sourceClass,
      final String sourceMethod,
      final String bundleName,
      final String msg,
      final Object param1) {}

  public void logrb(
      final Level level,
      final String sourceClass,
      final String sourceMethod,
      final String bundleName,
      final String msg,
      final Object[] params) {}

  public void logrb(
      final Level level,
      final String sourceClass,
      final String sourceMethod,
      final ResourceBundle bundle,
      final String msg,
      final Object... params) {}

  public void logrb(
      final Level level,
      final String sourceClass,
      final String sourceMethod,
      final String bundleName,
      final String msg,
      final Throwable thrown) {}

  public void logrb(
      final Level level,
      final String sourceClass,
      final String sourceMethod,
      final ResourceBundle bundle,
      final String msg,
      final Throwable thrown) {}

  public void severe(final String msg) {}

  public void warning(final String msg) {}

  public void info(final String msg) {}

  public void config(final String msg) {}

  public void fine(final String msg) {}

  public void finer(final String msg) {}

  public void finest(final String msg) {}

  public void throwing(
      final String sourceClass, final String sourceMethod, final Throwable thrown) {}

  public void setLevel(final Level newLevel) throws SecurityException {}

  public boolean isLoggable(final Level level) {
    return false;
  }
}
