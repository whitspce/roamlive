/*
 * Copyright (C) 2024 pedroSG94.
 * Modified by Whitespace in 2026 for Roam.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by pedro on 16/6/21.
 *
 * Replace Log class of Android for testing purpose
 */
public class Log {

  private static final List<String> messages = new CopyOnWriteArrayList<>();

  public static void clearMessages() {
    messages.clear();
  }

  public static List<String> getMessages() {
    return new ArrayList<>(messages);
  }

  public static int i(String tag, String message, Throwable throwable) {
    return println(tag, message, throwable);
  }

  public static int i(String tag, String message) {
    return println(tag, message, null);
  }

  public static int e(String tag, String message, Throwable throwable) {
    return printlnError(tag, message, throwable);
  }

  public static int e(String tag, String message) {
    return printlnError(tag, message, null);
  }

  private static int println(String tag, String message, Throwable throwable) {
    record(tag, message, throwable);
    System.out.println(tag + ": " + message);
    if (throwable != null) throwable.printStackTrace();
    return 0;
  }

  private static int printlnError(String tag, String message, Throwable throwable) {
    record(tag, message, throwable);
    System.err.println(tag + ": " + message);
    if (throwable != null) throwable.printStackTrace();
    return 0;
  }

  private static void record(String tag, String message, Throwable throwable) {
    String value = tag + ": " + message;
    if (throwable != null) value += " " + throwable;
    messages.add(value);
  }
}
