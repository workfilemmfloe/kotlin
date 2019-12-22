// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.hint;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.NonBlockingReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.progress.util.ProgressWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.LoadingDecorator;
import com.intellij.openapi.ui.popup.ComponentPopupBuilder;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.HintListener;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.EdtScheduledExecutorService;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.CancellablePromise;

import javax.swing.*;
import java.awt.*;
import java.util.EventObject;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class ParameterInfoTaskRunnerUtil {
  static <T> void runTask(Project project,
                          NonBlockingReadAction<T> nonBlockingReadAction,
                          Consumer<T> continuationConsumer,
                          String progressTitle,
                          int offset,
                          Editor editor) {
    AtomicReference<CancellablePromise<?>> cancellablePromiseRef = new AtomicReference<>();
    Consumer<Boolean> stopAction =
      startProgressAndCreateStopAction(editor.getProject(), progressTitle, cancellablePromiseRef, offset, editor);

    final VisibleAreaListener visibleAreaListener = new CancelProgressOnScrolling(cancellablePromiseRef);

    editor.getScrollingModel().addVisibleAreaListener(visibleAreaListener);

    final Component focusOwner = getFocusOwner(project);

    cancellablePromiseRef.set(
      nonBlockingReadAction.finishOnUiThread(
        ModalityState.defaultModalityState(),
        continuation -> {
          CancellablePromise<?> promise = cancellablePromiseRef.get();
          if (promise != null && promise.isSucceeded() && Objects.equals(focusOwner, getFocusOwner(project))) {
            continuationConsumer.accept(continuation);
          }
        })
        .submit(AppExecutorUtil.getAppExecutorService())
        .onProcessed(ignore -> {
          stopAction.accept(false);
          editor.getScrollingModel().removeVisibleAreaListener(visibleAreaListener);
        }));
  }

  private static Component getFocusOwner(Project project) {
    return IdeFocusManager.getInstance(project).getFocusOwner();
  }

  @NotNull
  private static Consumer<Boolean> startProgressAndCreateStopAction(Project project,
                                                                        String progressTitle,
                                                                        AtomicReference<CancellablePromise<?>> promiseRef,
                                                                        int offset,
                                                                        Editor editor) {
    offset = offset > 0 ? offset : editor.getCaretModel().getOffset() - 1;
    ParameterInfoController controller = ParameterInfoController.findControllerAtOffset(editor, offset);

    AtomicReference<Consumer<Boolean>> stopActionRef = new AtomicReference<>();

    Consumer<Boolean> originalStopAction = (cancel) -> {
      stopActionRef.set(null);
      if (cancel) {
        CancellablePromise<?> promise = promiseRef.get();
        if (promise != null) {
          promise.cancel();
        }
      }
    };

    HintListener hintListener = new HintListener() {
      @Override
      public void hintHidden(@NotNull EventObject event) {
        Consumer<Boolean> stopAction = stopActionRef.get();
        if (stopAction != null) {
          stopAction.accept(true);
        }
      }
    };

    if (controller != null && controller.showLoading(hintListener)) {
      stopActionRef.set((cancel) -> {
        try {
          controller.hideLoading(hintListener);
        } finally {
          originalStopAction.accept(cancel);
        }
      });
    } else {
      final Disposable disposable = Disposer.newDisposable();
      Disposer.register(project, disposable);

      JBLoadingPanel loadingPanel =
        new JBLoadingPanel(null, panel -> new LoadingDecorator(panel, disposable, 0, false, new AsyncProcessIcon("ShowParameterInfo")) {
          @Override
          protected NonOpaquePanel customizeLoadingLayer(JPanel parent, JLabel text, AsyncProcessIcon icon) {
            parent.setLayout(new FlowLayout(FlowLayout.LEFT));
            final NonOpaquePanel result = new NonOpaquePanel();
            result.add(icon);
            parent.add(result);
            return result;
          }
        });
      loadingPanel.add(new JBLabel(EmptyIcon.ICON_18));
      loadingPanel.add(new JBLabel(progressTitle));

      ComponentPopupBuilder builder =
        JBPopupFactory.getInstance().createComponentPopupBuilder(loadingPanel, null)
          .setProject(project)
          .setCancelCallback(() -> {
            Consumer<Boolean> stopAction = stopActionRef.get();
            if (stopAction != null) {
              stopAction.accept(true);
            }
            return true;
          });
      JBPopup popup = builder.createPopup();
      Disposer.register(disposable, popup);
      ScheduledFuture<?> showPopupFuture = EdtScheduledExecutorService.getInstance().schedule(() -> {
        if (!popup.isDisposed() && !popup.isVisible()) {
          RelativePoint popupPosition = JBPopupFactory.getInstance().guessBestPopupLocation(editor);
          loadingPanel.startLoading();
          popup.show(popupPosition);
        }
      }, ModalityState.defaultModalityState(), ProgressWindow.DEFAULT_PROGRESS_DIALOG_POSTPONE_TIME_MILLIS, TimeUnit.MILLISECONDS);

      stopActionRef.set((cancel) -> {
        try {
          loadingPanel.stopLoading();
          originalStopAction.accept(cancel);
        } finally {
          showPopupFuture.cancel(false);
          UIUtil.invokeLaterIfNeeded(() -> {
            if (popup.isVisible()) {
              popup.setUiVisible(false);
            }
            Disposer.dispose(disposable);
          });
        }
      });
    }

    return stopActionRef.get();
  }
}
