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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
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
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.runelite.cache.fs.Store;

/**
 * Plays a {@code .glb}'s animations the way the game will draw them.
 *
 * <p>Each animation is converted into the clip {@code generateAssets} would bundle - sampled at its
 * live sequence's frames - and posed by the plugin's own skinner, frame by frame with nothing in
 * between, each frame held for as many client cycles as the sequence says. The model is lit once at
 * rest and scaled and recolored as {@code models.json} says, as the plugin does. See
 * {@link AnimationDocument} for which sequence an animation plays against.
 *
 * <p>Run with {@code ./gradlew viewAnimations [-Pglb=<file>]}; without {@code -Pglb} it asks for a
 * file. It is authoring tooling in the test sourceSet and never ships, hence plain Swing and
 * {@link JFileChooser}.
 */
public class GltfAnimationViewer
{
	/** A client cycle, the unit the game times animation frames in. */
	private static final double NANOS_PER_CYCLE = SequenceTiming.SECONDS_PER_CYCLE * 1e9;

	private static final String[] SPEED_LABELS = {"1x", "0.5x", "0.25x"};
	private static final double[] SPEEDS = {1, 0.5, 0.25};

	private final Path path;
	private final AnimationDocument document;
	private final AnimationViewport viewport;

	private final JFrame window = new JFrame();
	private final JList<AnimationDocument.Animation> list;
	private final JToggleButton play = new JToggleButton("Play (Space)");
	private final JSlider slider = new JSlider(0, 0, 0);
	private final JCheckBox loop = new JCheckBox("Loop", true);
	private final JComboBox<String> speed = new JComboBox<>(SPEED_LABELS);
	private final JLabel status = new JLabel(" ");
	private final Timer timer = new Timer(20, e -> tick());

	private AnimationDocument.Animation current;
	private int shownFrame = -1;
	private boolean updatingSlider;

	/** Playback position in client cycles, as of {@link #baseNanos} while playing. */
	private double baseCycles;
	private long baseNanos;
	private double speedFactor = 1;

	public static void main(String[] args)
	{
		String glbArg = System.getProperty("customnpcmodels.glb");
		SwingUtilities.invokeLater(() ->
		{
			Path path = glbArg == null || glbArg.isEmpty() ? choose() : Paths.get(glbArg);
			if (path == null)
			{
				return;
			}
			try
			{
				new GltfAnimationViewer(path).window.setVisible(true);
			}
			catch (IOException | GltfException ex)
			{
				JOptionPane.showMessageDialog(null, "Could not open " + path.getFileName() + ":\n" + ex.getMessage(),
					"View animations", JOptionPane.ERROR_MESSAGE);
			}
		});
	}

	private static Path choose()
	{
		JFileChooser chooser = new JFileChooser(Paths.get("").toAbsolutePath().toFile());
		chooser.setDialogTitle("Open a model to view its animations");
		chooser.setFileFilter(new FileNameExtensionFilter("glTF binary (*.glb)", "glb"));
		return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
	}

	private GltfAnimationViewer(Path path) throws IOException
	{
		this.path = path.toAbsolutePath();
		byte[] glb = Files.readAllBytes(this.path);
		Manifest.Model entry = Manifest.entryFor(this.path);

		// The cache is only read for sequence timings, all of them now, so it is closed straight after
		try (Store store = CacheFiles.openLiveCache())
		{
			document = AnimationDocument.load(glb, entry,
				store == null ? null : sequenceId -> AssetGenerator.timing(store, sequenceId));
		}

		viewport = new AnimationViewport(document);
		List<Clip> clips = new ArrayList<>();
		for (AnimationDocument.Animation animation : document.playable())
		{
			clips.add(animation.clip);
		}
		viewport.frameClips(clips);

		list = new JList<>(document.animations().toArray(new AnimationDocument.Animation[0]));
		buildWindow();

		List<AnimationDocument.Animation> playable = document.playable();
		if (!playable.isEmpty())
		{
			list.setSelectedValue(playable.get(0), true);
			setPlaying(true);
		}
		else
		{
			select(null);
		}
	}

	// --- Layout ---------------------------------------------------------------------------------

	private void buildWindow()
	{
		window.setTitle(path.getFileName() + " - View animations");
		window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		window.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent e)
			{
				timer.stop();
			}
		});

		JPanel root = new JPanel(new BorderLayout());
		root.add(toolbar(), BorderLayout.NORTH);
		root.add(viewport, BorderLayout.CENTER);
		root.add(animationList(), BorderLayout.WEST);
		root.add(bottom(), BorderLayout.SOUTH);
		window.setContentPane(root);

		installMouse();
		installKeys(root);
		window.pack();
		window.setLocationRelativeTo(null);
	}

	private JComponent toolbar()
	{
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		play.addActionListener(e -> setPlaying(play.isSelected()));
		bar.add(unfocusable(play));
		bar.add(unfocusable(button("< Frame", e -> step(-1))));
		bar.add(unfocusable(button("Frame >", e -> step(1))));
		loop.setToolTipText("Off plays the sequence once and holds its last frame");
		bar.add(unfocusable(loop));
		bar.add(new JLabel("Speed"));
		speed.addActionListener(e -> setSpeed(SPEEDS[speed.getSelectedIndex()]));
		bar.add(unfocusable(speed));
		bar.add(Box.createHorizontalStrut(16));
		JCheckBox lighting = new JCheckBox("Game lighting", true);
		lighting.setToolTipText("Off shows each face's flat color, with no shading");
		lighting.addActionListener(e -> viewport.setGameLighting(lighting.isSelected()));
		bar.add(unfocusable(lighting));
		bar.add(unfocusable(button("Reset view (F)", e -> frameCurrent())));
		return bar;
	}

	private static JButton button(String label, ActionListener action)
	{
		JButton button = new JButton(label);
		button.addActionListener(action);
		return button;
	}

	/** Keeps keyboard focus on the view, so Space and the arrow keys always reach the player. */
	private static <T extends JComponent> T unfocusable(T component)
	{
		component.setFocusable(false);
		return component;
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
			if (!e.getValueIsAdjusting())
			{
				select(list.getSelectedValue());
			}
		});

		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
		JLabel heading = new JLabel("Animations");
		heading.setFont(heading.getFont().deriveFont(Font.BOLD));
		heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		panel.add(heading, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(list);
		scroll.setPreferredSize(new Dimension(280, 200));
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
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
				seek(Playback.startCycle(current.timing, slider.getValue()));
			}
		});
		panel.add(left(slider));
		panel.add(left(status));

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
			label.setForeground(new Color(0xB36B00));
			panel.add(left(label));
		}

		List<String> report = document.report();
		if (!report.isEmpty())
		{
			JTextArea text = new JTextArea(String.join("\n", report));
			text.setEditable(false);
			text.setLineWrap(true);
			text.setWrapStyleWord(true);
			JScrollPane scroll = new JScrollPane(text);
			scroll.setPreferredSize(new Dimension(600, 90));
			scroll.setVisible(false);

			JToggleButton toggle = new JToggleButton("Conversion report (" + report.size() + ")");
			toggle.setFocusable(false);
			toggle.addActionListener(e ->
			{
				scroll.setVisible(toggle.isSelected());
				panel.revalidate();
			});
			panel.add(Box.createVerticalStrut(4));
			panel.add(left(toggle));
			panel.add(left(scroll));
		}
		return panel;
	}

	private static JComponent left(JComponent component)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		return component;
	}

	// --- Input ----------------------------------------------------------------------------------

	private void installMouse()
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
				if (e.isShiftDown())
				{
					viewport.pan(dx, dy);
				}
				else
				{
					viewport.orbit(dx * 0.01, dy * 0.01);
				}
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e)
			{
				viewport.zoom(Math.pow(1.1, e.getPreciseWheelRotation()));
			}
		};
		viewport.addMouseListener(mouse);
		viewport.addMouseMotionListener(mouse);
		viewport.addMouseWheelListener(mouse);
	}

	private void installKeys(JComponent root)
	{
		bind(root, "SPACE", "play", () -> setPlaying(!play.isSelected()));
		bind(root, "LEFT", "back", () -> step(-1));
		bind(root, "RIGHT", "forward", () -> step(1));
		bind(root, "F", "frame", this::frameCurrent);
	}

	private static void bind(JComponent root, String key, String name, Runnable action)
	{
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
		root.getActionMap().put(name, new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				action.run();
			}
		});
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
			viewport.show(null, 0);
			status.setText(animation == null
				? (document.animations().isEmpty() ? "The file has no animations; showing the rest pose"
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
		if (playing && !loop.isSelected() && now >= Playback.cycles(current.timing) - 1)
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
		seek(Playback.startCycle(current.timing, frame));
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
		if (!loop.isSelected() && currentCycles() >= Playback.cycles(current.timing))
		{
			setPlaying(false);
			seek(Playback.cycles(current.timing) - 1);
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
			viewport.show(current.clip, frame);
			updatingSlider = true;
			slider.setValue(frame);
			updatingSlider = false;
		}

		long total = Playback.cycles(timing);
		long cycle = loop.isSelected() ? cycles % total : Math.min(cycles, total - 1);
		status.setText(String.format("Frame %d / %d   Cycle %d / %d   %.2f s / %.2f s   Sequence %d   "
				+ "Drag: turn   Shift+drag: move   Wheel: zoom   Left / Right: step",
			frame + 1, timing.frameCount(), cycle, total, cycle * SequenceTiming.SECONDS_PER_CYCLE, timing.duration(),
			current.sequenceId));
	}

	private void frameCurrent()
	{
		viewport.frameClips(current != null && current.isPlayable()
			? Collections.singletonList(current.clip) : Collections.emptyList());
	}
}
