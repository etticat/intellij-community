/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.jetbrains.commandInterface.console;

import com.intellij.execution.console.LanguageConsoleBuilder;
import com.intellij.execution.console.LanguageConsoleImpl;
import com.intellij.execution.console.LanguageConsoleView;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Condition;
import com.intellij.util.Consumer;
import com.jetbrains.commandInterface.command.Command;
import com.jetbrains.commandInterface.commandLine.CommandLineLanguage;
import com.jetbrains.commandInterface.commandLine.psi.CommandLineFile;
import com.jetbrains.python.psi.PyUtil;
import com.jetbrains.toolWindowWithActions.ConsoleWithProcess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * <h1>Command line console</h1>
 * <p>
 * Console that allows user to type commands and execute them.
 * </p>
 * <h2>2 modes of console</h2>
 * <p>There are 2 types of consoles: First one is based on document: it simply allows user to type something there. It also has prompt.
 * Second one is connected to process, hence it bridges all 3 streams between process and console.
 * This console, how ever, should support both modes and switch between them. So, it supports 2 modes:
 * <dl>
 * <dt>Command-mode</dt>
 * <dd>Console accepts user-input, treats it as commands and allows to execute em with aid of {@link CommandModeConsumer}.
 * In this mode it also has prompt and {@link CommandLineLanguage}.
 * </dd>
 * <dt>Process-mode</dt>
 * <dd>Activated when {@link #attachToProcess(ProcessHandler)} is called.
 * Console hides prompt, disables language and connects itself to process with aid of {@link ProcessModeConsumer}.
 * When process terminates, console switches back to command-mode (restoring prompt, etc)</dd>
 * </dl>
 * </p>
 *
 * @author Ilya.Kazakevich
 */
@SuppressWarnings({"DeserializableClassInSecureContext", "SerializableClassInSecureContext"}) // Nobody will serialize console
final class CommandConsole extends LanguageConsoleImpl implements Consumer<String>, Condition<LanguageConsoleView>, ConsoleWithProcess {
  /**
   * List of commands (to be injected into {@link CommandLineFile}) if any
   */
  @Nullable
  private final List<Command> myCommandList;
  @NotNull
  private final Module myModule;
  /**
   * {@link CommandModeConsumer} or {@link ProcessModeConsumer} to delegate execution to.
   * It also may be null if exection is not available.
   */
  @Nullable
  private Consumer<String> myCurrentConsumer;
  /**
   * One to sync action access to consumer field because it may be changed by callback when process is terminated
   */
  @NotNull
  private final Object myConsumerSemaphore = new Object();

  /**
   * Listener that will be notified when console state (mode?) changed.
   */
  @NotNull
  private final Collection<Runnable> myStateChangeListeners = new ArrayList<Runnable>();
  /**
   * Process handler currently running on console (if any)
   *
   * @see #switchToProcessMode(ProcessHandler)
   */
  @Nullable
  private volatile ProcessHandler myProcessHandler;

  /**
   * @param module      module console runs on
   * @param title       console title
   * @param commandList List of commands (to be injected into {@link CommandLineFile}) if any
   */
  private CommandConsole(@NotNull final Module module,
                         @NotNull final String title,
                         @Nullable final List<Command> commandList) {
    super(module.getProject(), title, CommandLineLanguage.INSTANCE);
    myCommandList = commandList;
    myModule = module;
  }

  /**
   * @param module      module console runs on
   * @param title       console title
   * @param commandList List of commands (to be injected into {@link CommandLineFile}) if any
   * @return console
   */
  @NotNull
  static CommandConsole createConsole(@NotNull final Module module,
                                      @NotNull final String title,
                                      @Nullable final List<Command> commandList) {
    final CommandConsole console = new CommandConsole(module, title, commandList);
    console.setEditable(true);
    LanguageConsoleBuilder.registerExecuteAction(console, console, title, title, console);

    console.switchToCommandMode();
    console.getComponent(); // For some reason console does not have component until this method is called which leads to some errros.
    return console;
  }

  @Override
  public void attachToProcess(final ProcessHandler processHandler) {
    super.attachToProcess(processHandler);
    processHandler.addProcessListener(new MyProcessListener());
  }

  /**
   * Switches console to "command-mode" (see class doc for details)
   */
  private void switchToCommandMode() {
    myProcessHandler = null;
    setPrompt(getTitle() + " > ");

    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        notifyStateChangeListeners();
        setLanguage(CommandLineLanguage.INSTANCE);
        final CommandLineFile file = PyUtil.as(getFile(), CommandLineFile.class);
        resetConsumer(null);
        if (file == null || myCommandList == null) {
          return;
        }
        file.setCommands(myCommandList);
        final CommandConsole console = CommandConsole.this;
        resetConsumer(new CommandModeConsumer(myCommandList, myModule, console));
      }
    }, ModalityState.NON_MODAL);
  }

  /**
   * Switches console to "process-mode" (see class doc for details)
   *
   * @param processHandler process to attach to
   */
  private void switchToProcessMode(@NotNull final ProcessHandler processHandler) {
    myProcessHandler = processHandler;
    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        notifyStateChangeListeners();
        resetConsumer(new ProcessModeConsumer(processHandler));
        // In process mode we do not need prompt and highlighting
        setLanguage(PlainTextLanguage.INSTANCE);
        setPrompt("");
      }
    }, ModalityState.NON_MODAL);
  }

  /**
   * Notify listeners that state has been changed
   */
  private void notifyStateChangeListeners() {
    for (final Runnable listener : myStateChangeListeners) {
      listener.run();
    }
  }

  /**
   * @return process handler currently running on console (if any) or null if in {@link #switchToCommandMode() command mode}
   * @see #switchToProcessMode(ProcessHandler)
   */
  @Override
  @Nullable
  public ProcessHandler getProcessHandler() {
    return myProcessHandler;
  }

  /**
   * Chooses consumer to delegate execute action to.
   *
   * @param newConsumer new consumer to register to delegate execution to
   *                    or null if just reset consumer
   */
  private void resetConsumer(@Nullable final Consumer<String> newConsumer) {
    synchronized (myConsumerSemaphore) {
      myCurrentConsumer = newConsumer;
    }
  }

  @Override
  public boolean value(final LanguageConsoleView t) {
    // Is execution available?
    synchronized (myConsumerSemaphore) {
      return myCurrentConsumer != null;
    }
  }

  @Override
  public void consume(final String t) {
    // User requested execution (enter clicked)
    synchronized (myConsumerSemaphore) {
      if (myCurrentConsumer != null) {
        myCurrentConsumer.consume(t);
      }
    }
  }

  /**
   * Adds listener that will be notified when console state (mode?) changed.
   * <strong>Called on EDT</strong>
   *
   * @param listener listener to notify
   */
  void addStateChangeListener(@NotNull final Runnable listener) {
    myStateChangeListeners.add(listener);
  }


  /**
   * Listens for process to switch between modes
   */
  private final class MyProcessListener extends ProcessAdapter {
    @Override
    public void processTerminated(@NotNull final ProcessEvent event) {
      super.processTerminated(event);
      switchToCommandMode();
    }

    @Override
    public void startNotified(@NotNull final ProcessEvent event) {
      super.startNotified(event);
      switchToProcessMode(event.getProcessHandler());
    }
  }

  @Override
   protected void setupEditorDefault(@NotNull final EditorEx editor) {
    super.setupEditorDefault(editor);
    // We do not need spaces here, because it leads to PY-15557
    final EditorSettings editorSettings = editor.getSettings();
    editorSettings.setAdditionalLinesCount(0);
    editorSettings.setAdditionalColumnsCount(0);
  }
}
