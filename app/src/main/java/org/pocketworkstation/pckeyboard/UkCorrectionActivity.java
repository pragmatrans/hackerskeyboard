/*
 * Copyright (C) 2026 The Hacker's Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pocketworkstation.pckeyboard;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * Teaches the keyboard one correction by hand: "I typed this, I meant that".
 *
 * Opened from the suggestion strip (long press a candidate) or from the settings screen.
 */
public class UkCorrectionActivity extends Activity {
    public static final String EXTRA_TYPED = "typed";
    public static final String EXTRA_CORRECT = "correct";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        final int pad = (int) (getResources().getDisplayMetrics().density * 16);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText typed = new EditText(this);
        typed.setHint("слово з одруківкою");
        typed.setInputType(InputType.TYPE_CLASS_TEXT);
        final EditText correct = new EditText(this);
        correct.setHint("правильне слово");
        correct.setInputType(InputType.TYPE_CLASS_TEXT);

        String typedExtra = getIntent().getStringExtra(EXTRA_TYPED);
        String correctExtra = getIntent().getStringExtra(EXTRA_CORRECT);
        typed.setText(typedExtra == null || typedExtra.length() == 0
                ? UkDictionary.lastUnknown() : typedExtra);
        correct.setText(correctExtra == null ? "" : correctExtra);

        box.addView(label("Так я набираю (помилково):"));
        box.addView(typed);
        box.addView(label("А маю на увазі:"));
        box.addView(correct);

        final Button save = new Button(this);
        save.setText("Зберегти заміну");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String wrong = typed.getText().toString().trim();
                final String right = correct.getText().toString().trim();
                if (wrong.length() < 2 || right.length() < 2) {
                    Toast.makeText(UkCorrectionActivity.this,
                            "Обидва слова потрібні", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (wrong.equalsIgnoreCase(right)) {
                    Toast.makeText(UkCorrectionActivity.this,
                            "Слова однакові", Toast.LENGTH_SHORT).show();
                    return;
                }
                UkTypoStore.put(UkCorrectionActivity.this, wrong, right);
                Toast.makeText(UkCorrectionActivity.this,
                        "Запам'ятав: " + wrong + " → " + right, Toast.LENGTH_LONG).show();
                finish();
            }
        });
        box.addView(save);

        final TextView saved = label("");
        final View.OnClickListener refresh = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saved.setText(list());
            }
        };
        box.addView(label("Уже збережено:"));
        box.addView(saved);
        refresh.onClick(null);

        final Button clear = new Button(this);
        clear.setText("Видалити всі");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UkTypoStore.clear(UkCorrectionActivity.this);
                saved.setText(list());
                Toast.makeText(UkCorrectionActivity.this, "Список очищено",
                        Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(clear);

        final Button cancel = new Button(this);
        cancel.setText("Закрити");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        box.addView(cancel);

        setContentView(box);
    }

    private String list() {
        final Map<String, ?> all = UkTypoStore.all(this);
        if (all.isEmpty()) {
            return "поки нічого";
        }
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.getKey()).append(" → ").append(entry.getValue());
        }
        return sb.toString();
    }

    private TextView label(String text) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setPadding(0, (int) (getResources().getDisplayMetrics().density * 8), 0, 0);
        return view;
    }
}
