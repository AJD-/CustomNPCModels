/*
 * Copyright (c) 2026, AJD
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.customnpcmodels.authoring;

import com.customnpcmodels.cache.CacheFiles;
import com.customnpcmodels.inject.Clip;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.runelite.cache.fs.Store;

/**
 * Plays a {@code .glb}'s animations the way the game will draw them.
 * <p>
 * Each animation is converted into the clip {@code generateAssets} would bundle - sampled at its
 * live sequence's frames - and posed by the plugin's own skinner, frame by frame with nothing in
 * between, each frame held for as many client cycles as the sequence says. The model is lit once at
 * rest and scaled and recolored as {@code models.json} says, as the plugin does. See
 * {@link AnimationDocument} for which sequence an animation plays against.
 * <p>
 * Given several models - picked together, or a folder, which opens every {@code .glb} directly in
 * it - it shows one tab each, loading each the first time its tab is shown. Ctrl+Page Up / Page Down
 * or Ctrl+(Shift+)Tab move between them; the playback settings in the toolbar apply to whichever is
 * shown.
 * <p>
 * Run with {@code ./gradlew viewAnimations [-Pglb=<file or folder>]}; without {@code -Pglb} it asks
 * for one or more files. It is authoring tooling in the test sourceSet and never ships, hence plain
 * Swing and {@link JFileChooser}.
 */
public class GltfAnimationViewer
{
	/** A client cycle, the unit the game times animation frames in. */
	private static final double NANOS_PER_CYCLE = SequenceTiming.SECONDS_PER_CYCLE * 1e9;

	private static final String[] SPEED_LABELS = {"1x", "0.5x", "0.25x"};
	private static final double[] SPEEDS = {1, 0.5, 0.25};

	private final List<ModelTab> tabs = new ArrayList<>();
	/** One tab per model; null for a single model. */
	private final JTabbedPane tabStrip;

	private final JFrame window = new JFrame();
	private final JToggleButton play = new JToggleButton("Play (Space)");
	private final JSlider slider = new JSlider(0, 0, 0);
	private final JCheckBox loop = new JCheckBox("Loop", true);
	private final JComboBox<String> speed = new JComboBox<>(SPEED_LABELS);
	private final JCheckBox lighting = new JCheckBox("Game lighting", true);
	private final JLabel status = new JLabel(" ");
	private final Timer timer = new Timer(20, e -> tick());

	/**
	 * The live cache, opened by the first model that needs sequence timings and kept until the window
	 * closes, so switching to a tab not yet shown doesn't read it all again. Null when there is none;
	 * {@link #cacheOpened} tells that apart from not opened yet.
	 */
	private Store cache;
	private boolean cacheOpened;

	/** The model being shown. */
	private ModelTab active;
	private AnimationDocument.Animation current;
	private int shownFrame = -1;
	private boolean updatingSlider;

	/** Playback position in client cycles, as of {@link #baseNanos} while playing. */
	private double baseCycles;
	private long baseNanos;
	private double speedFactor = 1;

	/** Orders models by file name, ignoring case. */
	private static final Comparator<Path> BY_NAME =
		Comparator.comparing(glb -> glb.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);

	public static void main(String[] args)
	{
		String glbArg = System.getProperty("customnpcmodels.glb");
		SwingUtilities.invokeLater(() ->
		{
			Path argPath = glbArg == null || glbArg.isEmpty() ? null : Paths.get(glbArg);
			List<Path> models = Collections.emptyList();
			try
			{
				if (argPath == null)
				{
					models = choose();
				}
				else if (Files.isDirectory(argPath))
				{
					models = glbsIn(argPath);
					if (models.isEmpty())
					{
						throw new IOException("there are no .glb files in it");
					}
				}
				else
				{
					models = Collections.singletonList(argPath);
				}
				if (!models.isEmpty())
				{
					new GltfAnimationViewer(models).window.setVisible(true);
				}
			}
			catch (IOException | GltfException ex)
			{
				// Only a lone model or an empty folder throws; with several, each tab reports its own
				Path failed = models.size() == 1 ? models.get(0) : argPath;
				String name = failed == null ? "the model" : failed.getFileName().toString();
				JOptionPane.showMessageDialog(null, "Could not open " + name + ":\n" + ex.getMessage(),
					"View animations", JOptionPane.ERROR_MESSAGE);
			}
		});
	}

	/** Asks for one or more models, by name, or none when cancelled. */
	private static List<Path> choose()
	{
		JFileChooser chooser = new JFileChooser(Paths.get("").toAbsolutePath().toFile());
		chooser.setDialogTitle("Open models to view their animations (Ctrl+A selects the whole folder)");
		chooser.setMultiSelectionEnabled(true);
		chooser.setFileFilter(new FileNameExtensionFilter("glTF binary (*.glb)", "glb"));
		if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION)
		{
			return Collections.emptyList();
		}
		List<Path> models = new ArrayList<>();
		for (File file : chooser.getSelectedFiles())
		{
			models.add(file.toPath());
		}
		models.sort(BY_NAME);
		return models;
	}

	/** The {@code .glb} files directly in a folder, by name. */
	static List<Path> glbsIn(Path dir) throws IOException
	{
		List<Path> glbs = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir))
		{
			for (Path entry : entries)
			{
				if (Files.isRegularFile(entry) && entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".glb"))
				{
					glbs.add(entry);
				}
			}
		}
		glbs.sort(BY_NAME);
		return glbs;
	}

	/**
	 * @param models the models to show, as tabs when there are several; a lone model must load or
	 *               this throws
	 */
	private GltfAnimationViewer(List<Path> models) throws IOException
	{
		for (Path model : models)
		{
			tabs.add(new ModelTab(model));
		}

		JComponent center;
		if (tabs.size() == 1)
		{
			tabStrip = null;
			tabs.get(0).load();
			center = tabs.get(0).panel;
		}
		else
		{
			tabStrip = new JTabbedPane(SwingConstants.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
			tabStrip.setFocusable(false);
			for (ModelTab tab : tabs)
			{
				tabStrip.addTab(tab.path.getFileName().toString(), null, tab.panel, tab.path.toString());
			}
			center = tabStrip;
		}

		// Loaded and shown before packing, so the window is sized to a real view
		activate(tabs.get(0));
		setPlaying(true);
		if (tabStrip != null)
		{
			tabStrip.addChangeListener(e -> activate(tabs.get(tabStrip.getSelectedIndex())));
		}
		buildWindow(center);
	}

	/** The live cache, opened on first use. Null when there is none. */
	private Store cache() throws IOException
	{
		if (!cacheOpened)
		{
			cache = CacheFiles.openLiveCache();
			cacheOpened = true;
		}
		return cache;
	}

	/** Shows a model, loading it first if this is the first time, and plays its selected animation. */
	private void activate(ModelTab tab)
	{
		if (tab.document == null && tab.error == null)
		{
			tab.tryLoad();
		}
		active = tab;
		window.setTitle(tab.path.getFileName() + (tabStrip == null ? "" : " - " + tab.path.getParent().getFileName())
			+ " - View animations");
		select(tab.list == null ? null : tab.list.getSelectedValue());
		if (tab.viewport != null)
		{
			tab.viewport.requestFocusInWindow();
		}
	}

	/** Moves to the next or previous model, wrapping around. */
	private void cycleModel(int delta)
	{
		if (tabStrip != null)
		{
			tabStrip.setSelectedIndex(Math.floorMod(tabStrip.getSelectedIndex() + delta, tabs.size()));
		}
	}

	// --- Layout ---------------------------------------------------------------------------------

	private void buildWindow(JComponent center)
	{
		window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		window.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent e)
			{
				timer.stop();
				if (cache != null)
				{
					try
					{
						cache.close();
					}
					catch (IOException ex)
					{
						// Only read from, and the tool is ending anyway
						System.err.println("Could not close the live cache: " + ex.getMessage());
					}
				}
			}
		});

		JPanel root = new JPanel(new BorderLayout());
		root.add(toolbar(), BorderLayout.NORTH);
		root.add(center, BorderLayout.CENTER);
		root.add(bottom(), BorderLayout.SOUTH);
		window.setContentPane(root);

		installKeys(root);
		window.pack();
		window.setLocationRelativeTo(null);
	}

	private JComponent toolbar()
	{
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		play.addActionListener(e -> setPlaying(play.isSelected()));
		bar.add(unfocusable(play));
		bar.add(unfocusable(ToolWindows.button("< Frame", e -> step(-1))));
		bar.add(unfocusable(ToolWindows.button("Frame >", e -> step(1))));
		loop.setToolTipText("Off plays the sequence once and holds its last frame");
		bar.add(unfocusable(loop));
		bar.add(new JLabel("Speed"));
		speed.addActionListener(e -> setSpeed(SPEEDS[speed.getSelectedIndex()]));
		bar.add(unfocusable(speed));
		bar.add(Box.createHorizontalStrut(16));
		lighting.setToolTipText("Off shows each face's flat color, with no shading");
		lighting.addActionListener(e ->
		{
			for (ModelTab tab : tabs)
			{
				if (tab.viewport != null)
				{
					tab.viewport.setGameLighting(lighting.isSelected());
				}
			}
		});
		bar.add(unfocusable(lighting));
		bar.add(unfocusable(ToolWindows.button("Reset view (F)", e -> frameCurrent())));
		return bar;
	}

	/** Keeps keyboard focus on the view, so Space and the arrow keys always reach the player. */
	private static <T extends JComponent> T unfocusable(T component)
	{
		component.setFocusable(false);
		return component;
	}

	private static String describe(AnimationDocument.Animation animation)
	{
		if (!animation.isPlayable())
		{
			return "<html>" + escape(animation.name) + "<br><small>" + escape(animation.unplayableReason) + "</small></html>";
		}
		return String.format("<html>%s<br><small>seq %d &middot; %d frames &middot; %.2f s</small></html>",
			escape(animation.name), animation.sequenceId, animation.timing.frameCount(), animation.timing.duration());
	}

	private static String escape(String text)
	{
		return text == null ? "(unnamed)" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private JComponent bottom()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));

		slider.setFocusable(false);
		slider.addChangeListener(e ->
		{
			if (!updatingSlider && current != null && current.isPlayable())
			{
				setPlaying(false);
				seek(current.timing.startCycle(slider.getValue()));
			}
		});
		panel.add(ToolWindows.left(slider));
		panel.add(ToolWindows.left(status));
		return panel;
	}

	// --- Input ----------------------------------------------------------------------------------

	private static void installMouse(AnimationViewport viewport)
	{
		MouseAdapter mouse = new MouseAdapter()
		{
			private int lastX;
			private int lastY;

			@Override
			public void mousePressed(MouseEvent e)
			{
				lastX = e.getX();
				lastY = e.getY();
				viewport.requestFocusInWindow();
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				int dx = e.getX() - lastX;
				int dy = e.getY() - lastY;
				lastX = e.getX();
				lastY = e.getY();
				viewport.drag(dx, dy, e.isShiftDown());
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e)
			{
				viewport.wheel(e.getPreciseWheelRotation());
			}
		};
		viewport.addMouseListener(mouse);
		viewport.addMouseMotionListener(mouse);
		viewport.addMouseWheelListener(mouse);
	}

	private void installKeys(JComponent root)
	{
		ToolWindows.bind(root, "SPACE", "play", () -> setPlaying(!play.isSelected()));
		ToolWindows.bind(root, "LEFT", "back", () -> step(-1));
		ToolWindows.bind(root, "RIGHT", "forward", () -> step(1));
		ToolWindows.bind(root, "F", "frame", this::frameCurrent);
		if (tabStrip == null)
		{
			return;
		}

		ToolWindows.bind(root, "ctrl PAGE_DOWN", "nextModel", () -> cycleModel(1));
		ToolWindows.bind(root, "ctrl PAGE_UP", "previousModel", () -> cycleModel(-1));
		ToolWindows.bind(root, "ctrl TAB", "nextModelTab", () -> cycleModel(1));
		ToolWindows.bind(root, "ctrl shift TAB", "previousModelTab", () -> cycleModel(-1));
		// The focus manager would otherwise take Ctrl+Tab for itself, and the tab strip Ctrl+Page Up/Down
		window.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
			Collections.singleton(KeyStroke.getKeyStroke("TAB")));
		window.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
			Collections.singleton(KeyStroke.getKeyStroke("shift TAB")));
		for (String key : new String[]{"ctrl PAGE_DOWN", "ctrl PAGE_UP"})
		{
			tabStrip.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), "none");
		}
	}

	// --- Playback -------------------------------------------------------------------------------

	private void select(AnimationDocument.Animation animation)
	{
		boolean wasPlaying = play.isSelected();
		current = animation;
		shownFrame = -1;
		boolean playable = animation != null && animation.isPlayable();

		updatingSlider = true;
		slider.setMaximum(playable ? animation.clip.getFrameCount() - 1 : 0);
		slider.setValue(0);
		updatingSlider = false;
		play.setEnabled(playable);
		slider.setEnabled(playable);

		if (!playable)
		{
			setPlaying(false);
			if (active.document == null)
			{
				status.setText("Could not open " + active.path.getFileName() + ": " + active.error);
				return;
			}
			active.viewport.show(null, 0);
			status.setText(animation == null
				? (active.document.animations().isEmpty() ? "The file has no animations; showing the rest pose"
				: "No animation can be played; showing the rest pose")
				: "Can't play '" + animation.name + "': " + animation.unplayableReason + ". Showing the rest pose.");
			return;
		}
		seek(0);
		setPlaying(wasPlaying);
	}

	private void setPlaying(boolean playing)
	{
		playing &= current != null && current.isPlayable();
		double now = currentCycles();
		if (playing && !loop.isSelected() && now >= current.timing.cycles() - 1)
		{
			// Played through once already: start again from the top
			now = 0;
		}
		baseCycles = now;
		baseNanos = System.nanoTime();
		play.setSelected(playing);
		play.setText(playing ? "Pause (Space)" : "Play (Space)");
		if (playing)
		{
			timer.start();
		}
		else
		{
			timer.stop();
		}
		refresh();
	}

	/** Restarts the clock at the current position before changing speed, so the change does not jump. */
	private void setSpeed(double factor)
	{
		baseCycles = currentCycles();
		baseNanos = System.nanoTime();
		speedFactor = factor;
	}

	private double currentCycles()
	{
		if (!play.isSelected())
		{
			return baseCycles;
		}
		return baseCycles + (System.nanoTime() - baseNanos) / NANOS_PER_CYCLE * speedFactor;
	}

	private void seek(long cycle)
	{
		baseCycles = cycle;
		baseNanos = System.nanoTime();
		refresh();
	}

	/** Moves one frame back or forward, pausing playback. */
	private void step(int delta)
	{
		if (current == null || !current.isPlayable())
		{
			return;
		}
		setPlaying(false);
		int frames = current.clip.getFrameCount();
		int frame = Math.floorMod(frameNow() + delta, frames);
		seek(current.timing.startCycle(frame));
	}

	private int frameNow()
	{
		return Playback.frameAt(current.timing, (long) currentCycles(), loop.isSelected());
	}

	private void tick()
	{
		if (current == null || !current.isPlayable())
		{
			return;
		}
		if (!loop.isSelected() && currentCycles() >= current.timing.cycles())
		{
			setPlaying(false);
			seek(current.timing.cycles() - 1);
			return;
		}
		refresh();
	}

	/** Shows the frame for the current position, posing only when it has changed. */
	private void refresh()
	{
		if (current == null || !current.isPlayable())
		{
			return;
		}
		SequenceTiming timing = current.timing;
		long cycles = (long) currentCycles();
		int frame = Playback.frameAt(timing, cycles, loop.isSelected());
		if (frame != shownFrame)
		{
			shownFrame = frame;
			active.viewport.show(current.clip, frame);
			updatingSlider = true;
			slider.setValue(frame);
			updatingSlider = false;
		}

		long total = timing.cycles();
		long cycle = loop.isSelected() ? cycles % total : Math.min(cycles, total - 1);
		status.setText(String.format("Frame %d / %d   Cycle %d / %d   %.2f s / %.2f s   Sequence %d   "
				+ "Drag: turn   Shift+drag: move   Wheel: zoom   Left / Right: step%s",
			frame + 1, timing.frameCount(), cycle, total, cycle * SequenceTiming.SECONDS_PER_CYCLE, timing.duration(),
			current.sequenceId, tabs.size() > 1 ? "   Ctrl+PgUp / PgDn: model" : ""));
	}

	private void frameCurrent()
	{
		if (active.viewport != null)
		{
			active.viewport.frameClips(current != null && current.isPlayable()
				? Collections.singletonList(current.clip) : Collections.emptyList());
		}
	}

	// --- Models ---------------------------------------------------------------------------------

	/**
	 * One model: its document, view and animation list, built when it is first shown. Its view keeps
	 * its own camera and its list its own selection while other models are shown.
	 */
	private class ModelTab
	{
		final Path path;
		/** Holds the view once loaded, or the reason it could not be. */
		final JPanel panel = new JPanel(new BorderLayout());

		AnimationDocument document;
		AnimationViewport viewport;
		JList<AnimationDocument.Animation> list;
		/** Why the model could not be loaded, or null. */
		String error;

		ModelTab(Path path)
		{
			this.path = path.toAbsolutePath();
		}

		/** Loads the model, or shows why it could not be in its place. */
		void tryLoad()
		{
			try
			{
				load();
			}
			catch (IOException | RuntimeException ex)
			{
				document = null;
				viewport = null;
				list = null;
				panel.removeAll();
				error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
				JLabel label = new JLabel("<html>Could not open " + escape(path.getFileName().toString()) + ":<br>"
					+ escape(error) + "</html>", SwingConstants.CENTER);
				label.setPreferredSize(new Dimension(ModelViewport.PREFERRED_WIDTH, ModelViewport.PREFERRED_HEIGHT));
				panel.add(label, BorderLayout.CENTER);
			}
			// Already in the tab strip, unless this is the first model
			panel.revalidate();
		}

		void load() throws IOException
		{
			byte[] glb = Files.readAllBytes(path);
			Manifest.Model entry = Manifest.entryFor(path);

			Store store = cache();
			document = AnimationDocument.load(glb, entry,
				store == null ? null : sequenceId -> AssetGenerator.timing(store, sequenceId));

			viewport = new AnimationViewport(document);
			viewport.setGameLighting(lighting.isSelected());
			List<Clip> clips = new ArrayList<>();
			for (AnimationDocument.Animation animation : document.playable())
			{
				clips.add(animation.clip);
			}
			viewport.frameClips(clips);
			installMouse(viewport);

			list = new JList<>(document.animations().toArray(new AnimationDocument.Animation[0]));
			panel.add(animationList(), BorderLayout.WEST);
			panel.add(viewport, BorderLayout.CENTER);
			JComponent notes = notes();
			if (notes != null)
			{
				panel.add(notes, BorderLayout.SOUTH);
			}

			List<AnimationDocument.Animation> playable = document.playable();
			if (!playable.isEmpty())
			{
				list.setSelectedValue(playable.get(0), true);
			}
		}

		private JComponent animationList()
		{
			list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			list.setFocusable(false);
			list.setCellRenderer(new DefaultListCellRenderer()
			{
				@Override
				public Component getListCellRendererComponent(JList<?> jList, Object value, int index, boolean selected,
					boolean focused)
				{
					AnimationDocument.Animation animation = (AnimationDocument.Animation) value;
					JLabel label = (JLabel) super.getListCellRendererComponent(jList, describe(animation), index, selected, focused);
					label.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
					if (!animation.isPlayable())
					{
						label.setForeground(Color.GRAY);
						label.setToolTipText(animation.unplayableReason);
					}
					else
					{
						label.setToolTipText(null);
					}
					return label;
				}
			});
			list.addListSelectionListener(e ->
			{
				if (!e.getValueIsAdjusting() && active == this)
				{
					select(list.getSelectedValue());
				}
			});

			JPanel box = new JPanel(new BorderLayout());
			box.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
			JLabel heading = new JLabel("Animations");
			heading.setFont(heading.getFont().deriveFont(Font.BOLD));
			heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
			box.add(heading, BorderLayout.NORTH);
			JScrollPane scroll = new JScrollPane(list);
			scroll.setPreferredSize(new Dimension(280, 200));
			box.add(scroll, BorderLayout.CENTER);
			return box;
		}

		/** What models.json does to this model and the conversion report, or null when neither applies. */
		private JComponent notes()
		{
			JPanel box = new JPanel();
			box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
			box.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 8));

			List<String> notes = new ArrayList<>();
			if (document.isRecolored())
			{
				notes.add("models.json recolors this model; the colors shown are the recolored ones.");
			}
			if (document.scaleXZ() != 1f || document.scaleY() != 1f)
			{
				notes.add(String.format("models.json scales this model by %.2f across and %.2f up, after posing, as the game does.",
					document.scaleXZ(), document.scaleY()));
			}
			for (String note : notes)
			{
				JLabel label = new JLabel(note);
				label.setForeground(ToolWindows.WARNING);
				box.add(ToolWindows.left(label));
			}

			List<String> report = document.report();
			if (!report.isEmpty())
			{
				JTextArea text = new JTextArea(String.join("\n", report));
				text.setEditable(false);
				text.setLineWrap(true);
				text.setWrapStyleWord(true);
				// Leaves Ctrl+Tab to move between models when the report has focus
				text.setFocusTraversalKeysEnabled(false);
				JScrollPane scroll = new JScrollPane(text);
				scroll.setPreferredSize(new Dimension(600, 90));
				scroll.setVisible(false);

				JToggleButton toggle = new JToggleButton("Conversion report (" + report.size() + ")");
				toggle.setFocusable(false);
				toggle.addActionListener(e ->
				{
					scroll.setVisible(toggle.isSelected());
					box.revalidate();
				});
				box.add(ToolWindows.left(toggle));
				box.add(ToolWindows.left(scroll));
			}
			return notes.isEmpty() && report.isEmpty() ? null : box;
		}
	}
}
