package pagewright;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The document being edited: a private temp copy of the user's file plus the annotations laid over it,
 * with undo/redo. The user's own file is never touched except by an explicit save.
 */
final class Session {

    static final int MAX_UNDO = 30;

    static final class State {
        final File file;
        final List<Annotation> annotations;
        final long id;

        State(File file, List<Annotation> annotations, long id) {
            this.file = file;
            this.annotations = annotations;
            this.id = id;
        }
    }

    File file;
    List<Annotation> annotations = new ArrayList<>();
    File originalFile;
    File lastSavedTo;

    private long id;
    private long nextId = 1;
    private long cleanId;
    private Object lastKey;
    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();

    boolean isOpen() {
        return file != null;
    }

    boolean isDirty() {
        return file != null && id != cleanId;
    }

    long currentId() {
        return id;
    }

    void markClean(long savedId) {
        cleanId = savedId;
        // The next edit must start a new state even if it repeats the key of an edit made before the save.
        lastKey = null;
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    State peekUndo() {
        return undo.peek();
    }

    State peekRedo() {
        return redo.peek();
    }

    /** Starts a fresh session on a new temp working copy, discarding all previous history. */
    void reset(File working, File original) {
        List<File> old = new ArrayList<>();
        old.add(file);
        undo.forEach(s -> old.add(s.file));
        redo.forEach(s -> old.add(s.file));
        undo.clear();
        redo.clear();
        file = working;
        originalFile = original;
        lastSavedTo = null;
        annotations = new ArrayList<>();
        lastKey = null;
        id = nextId++;
        cleanId = id;
        for (File f : old) {
            if (f != null && !f.equals(working)) {
                f.delete();
            }
        }
    }

    /**
     * Call before changing annotations or the file. Consecutive changes with the same non-null key
     * (for example typing in one text box) share a single undo step.
     */
    void beginChange(Object key) {
        if (key != null && key.equals(lastKey)) {
            return;
        }
        undo.push(capture());
        dropRedo();
        while (undo.size() > MAX_UNDO) {
            release(undo.removeLast().file);
        }
        id = nextId++;
        lastKey = key;
    }

    /** Replaces the working file (an edit that rewrote the PDF), keeping the previous one for undo. */
    void replaceFile(File newFile, List<Annotation> newAnnotations) {
        beginChange(null);
        file = newFile;
        annotations = newAnnotations;
    }

    /** Moves back one step; the caller must already have loaded peekUndo().file. */
    void undo() {
        redo.push(capture());
        restore(undo.pop());
    }

    /** Moves forward one step; the caller must already have loaded peekRedo().file. */
    void redo() {
        undo.push(capture());
        restore(redo.pop());
    }

    static List<Annotation> copyOf(List<Annotation> list) {
        List<Annotation> copy = new ArrayList<>();
        for (Annotation a : list) {
            copy.add(a.copy());
        }
        return copy;
    }

    private State capture() {
        return new State(file, copyOf(annotations), id);
    }

    private void restore(State s) {
        file = s.file;
        annotations = copyOf(s.annotations);
        id = s.id;
        lastKey = null;
    }

    private void dropRedo() {
        while (!redo.isEmpty()) {
            release(redo.pop().file);
        }
    }

    /** Deletes a temp file once no state refers to it any more. */
    private void release(File f) {
        if (f == null || f.equals(file)) {
            return;
        }
        for (State s : undo) {
            if (s.file.equals(f)) {
                return;
            }
        }
        for (State s : redo) {
            if (s.file.equals(f)) {
                return;
            }
        }
        f.delete();
    }
}
