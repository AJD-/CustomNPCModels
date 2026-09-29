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

import com.customnpcmodels.inject.Mesh;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * A face painter for NPC {@code .glb} files: open, click faces to recolor them, save.
 * <p>
 * Every color it offers is a packed engine HSL, so what is picked is what the game draws, and the
 * model is lit the way the plugin lights it. Saving goes through {@link GlbPaintDocument}, which
 * changes nothing but the colors and checks the result before writing. The first save keeps the
 * original beside it as {@code <file>.bak}.
 * <p>
 * Run with {@code ./gradlew paintGltf [-Pglb=<file>]}; without {@code -Pglb} it asks for a file.
 * It is authoring tooling in the test sourceSet and never ships, hence plain Swing and
 * {@link JFileChooser}.
 */
public class GltfPainter
{
	private enum Tool
	{
		BRUSH, FILL, EYEDROPPER
	}

	/** One face's change, for undo. */
	private static final class Change
	{
		final int face;
		final short before;
		final short after;

		Change(int face, short before, short after)
		{
			this.face = face;
			this.before = before;
			this.after = after;
		}
	}

	private static final int SWATCH = 22;
	private static final int MAX_SWATCHES = 64;
	private static final int MAX_RECENT = 12;

	private final Path path;
	private GlbPaintDocument document;
	private final PaintViewport viewport;
	private final String notice;

	private final JFrame window = new JFrame();
	private final JLabel status = new JLabel(" ");
	private final JPanel currentSwatch = new JPanel();
	private final JLabel currentLabel = new JLabel();
	private final JSlider hue = new JSlider(0, 63, 0);
	private final JSlider saturation = new JSlider(0, 7, 0);
	private final JSlider lightness = new JSlider(RsColor.MIN_LUMINANCE, RsColor.MAX_LUMINANCE, 64);
	private final JPanel modelColors = new JPanel(new GridLayout(0, 8, 2, 2));
	private final JPanel recentColors = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
	private final List<Short> recent = new ArrayList<>();

	private Tool tool = Tool.BRUSH;
	private short current;
	private boolean updatingSliders;

	private final Deque<List<Change>> undo = new ArrayDeque<>();
	private final Deque<List<Change>> redo = new ArrayDeque<>();
	private List<Change> stroke;

	/** Per face, the faces sharing an edge with it; built on first fill. */
	private List<int[]> neighbours;

	/** Faces of the parts unticked in the Parts list. */
	private final BitSet hiddenFaces = new BitSet();

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
				new GltfPainter(path).window.setVisible(true);
			}
			catch (IOException | GltfException ex)
			{
				JOptionPane.showMessageDialog(null, "Could not open " + path.getFileName() + ":\n" + ex.getMessage(),
					"Paint glTF", JOptionPane.ERROR_MESSAGE);
			}
		});
	}

	private static Path choose()
	{
		JFileChooser chooser = new JFileChooser(Paths.get("").toAbsolutePath().toFile());
		chooser.setDialogTitle("Open a model to paint");
		chooser.setFileFilter(new FileNameExtensionFilter("glTF binary (*.glb)", "glb"));
		return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
	}

	private GltfPainter(Path path) throws IOException
	{
		this.path = path.toAbsolutePath();
		document = GlbPaintDocument.load(Files.readAllBytes(this.path));

		Manifest.Model entry = Manifest.entryFor(this.path);
		int ambient = entry != null && entry.ambient != null ? entry.ambient : 0;
		int contrast = entry != null && entry.contrast != null ? entry.contrast : 0;
		notice = entry != null && entry.recolors != null && !entry.recolors.isEmpty()
			? "models.json recolors " + entry.recolors.size() + " color(s) of this model in game, on top of what is painted here."
			: null;

		viewport = new PaintViewport(document, ambient, contrast);
		buildWindow();
		setCurrent(mostUsedColor());
		refreshModelColors();
		updateTitle();
	}

	// --- Layout ---------------------------------------------------------------------------------

	private void buildWindow()
	{
		window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		window.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				if (confirmDiscard())
				{
					window.dispose();
				}
			}
		});

		JPanel root = new JPanel(new BorderLayout());
		root.add(toolbar(), BorderLayout.NORTH);
		root.add(viewport, BorderLayout.CENTER);
		root.add(sidePanel(), BorderLayout.EAST);
		status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		root.add(status, BorderLayout.SOUTH);
		window.setContentPane(root);

		installMouse();
		installKeys(root);
		window.pack();
		window.setLocationRelativeTo(null);
		showHelp();
	}

	private JComponent toolbar()
	{
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		ButtonGroup group = new ButtonGroup();
		bar.add(toolButton("Brush (B)", Tool.BRUSH, group, true));
		bar.add(toolButton("Fill (G)", Tool.FILL, group, false));
		bar.add(toolButton("Pick color (I)", Tool.EYEDROPPER, group, false));
		bar.add(Box.createHorizontalStrut(16));
		bar.add(button("Undo", e -> undo()));
		bar.add(button("Redo", e -> redo()));
		bar.add(button("Save", e -> save()));
		bar.add(button("Reset view (F)", e -> viewport.frame()));
		JCheckBox lighting = new JCheckBox("Game lighting", true);
		lighting.setToolTipText("Off shows each face's flat color, with no shading");
		lighting.addActionListener(e -> viewport.setGameLighting(lighting.isSelected()));
		bar.add(lighting);
		return bar;
	}

	private JToggleButton toolButton(String label, Tool value, ButtonGroup group, boolean selected)
	{
		JToggleButton button = new JToggleButton(label, selected);
		button.addActionListener(e -> tool = value);
		button.putClientProperty(Tool.class, value);
		group.add(button);
		return button;
	}

	private static JButton button(String label, ActionListener action)
	{
		JButton button = new JButton(label);
		button.addActionListener(action);
		return button;
	}

	private JComponent sidePanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		panel.add(heading("Current color"));
		currentSwatch.setPreferredSize(new Dimension(220, 48));
		currentSwatch.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
		currentSwatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		panel.add(left(currentSwatch));
		panel.add(left(currentLabel));
		panel.add(Box.createVerticalStrut(6));

		panel.add(left(new JLabel("Hue")));
		panel.add(left(hue));
		panel.add(left(new JLabel("Saturation")));
		panel.add(left(saturation));
		panel.add(left(new JLabel("Lightness")));
		panel.add(left(lightness));
		for (JSlider slider : new JSlider[]{hue, saturation, lightness})
		{
			slider.addChangeListener(e -> slidersChanged());
		}
		panel.add(Box.createVerticalStrut(10));

		panel.add(heading("Colors in this model"));
		JPanel modelColorsHolder = new JPanel(new BorderLayout());
		modelColorsHolder.add(modelColors, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(modelColorsHolder);
		scroll.setPreferredSize(new Dimension(220, 200));
		scroll.setBorder(BorderFactory.createEmptyBorder());
		panel.add(left(scroll));
		panel.add(Box.createVerticalStrut(10));

		panel.add(heading("Recently used"));
		recentColors.setPreferredSize(new Dimension(220, 52));
		panel.add(left(recentColors));

		List<MeshPart> parts = document.parts();
		if (parts.size() > 1)
		{
			panel.add(Box.createVerticalStrut(10));
			panel.add(heading("Parts"));
			JPanel list = new JPanel();
			list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
			for (MeshPart part : parts)
			{
				JCheckBox shown = new JCheckBox(part.name + " (" + part.faceCount + " faces)", true);
				shown.setToolTipText("Untick to hide this part while painting; it is still saved");
				shown.addActionListener(e -> showPart(part, shown.isSelected()));
				list.add(shown);
			}
			JScrollPane partsScroll = new JScrollPane(list);
			partsScroll.setPreferredSize(new Dimension(220, Math.min(160, parts.size() * 24 + 4)));
			partsScroll.setBorder(BorderFactory.createEmptyBorder());
			panel.add(left(partsScroll));
		}

		if (notice != null)
		{
			panel.add(Box.createVerticalStrut(10));
			JLabel label = new JLabel("<html><div style='width:200px'>" + notice + "</div></html>");
			label.setForeground(new Color(0xB36B00));
			panel.add(left(label));
		}
		panel.add(Box.createVerticalGlue());
		return panel;
	}

	/** Hides or shows a part's faces; hidden faces cannot be painted, filled into or picked from. */
	private void showPart(MeshPart part, boolean shown)
	{
		hiddenFaces.set(part.firstFace, part.firstFace + part.faceCount, !shown);
		viewport.setHiddenFaces(hiddenFaces);
	}

	private static JLabel heading(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(Font.BOLD));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JComponent left(JComponent component)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		return component;
	}

	private JComponent swatch(short hsl)
	{
		JPanel swatch = new JPanel();
		swatch.setPreferredSize(new Dimension(SWATCH, SWATCH));
		swatch.setBackground(new Color(RsColor.hslToRgb(hsl & 0xFFFF)));
		swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		swatch.setToolTipText(describe(hsl));
		swatch.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		swatch.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				setCurrent(hsl);
			}
		});
		return swatch;
	}

	private void showHelp()
	{
		status.setText("Left-click or drag: paint   Alt+click: pick a face's color   Middle- or right-drag: turn   "
			+ "Shift+middle- or Shift+right-drag: move   Wheel: zoom   Ctrl+Z / Ctrl+Y: undo / redo   Ctrl+S: save");
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
				if (SwingUtilities.isLeftMouseButton(e))
				{
					int face = viewport.faceAt(e.getX(), e.getY());
					if (e.isAltDown() || tool == Tool.EYEDROPPER)
					{
						if (face >= 0)
						{
							setCurrent(document.color(face));
						}
						return;
					}
					stroke = new ArrayList<>();
					if (tool == Tool.FILL)
					{
						fill(face);
					}
					else
					{
						paint(face);
					}
				}
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				int dx = e.getX() - lastX;
				int dy = e.getY() - lastY;
				lastX = e.getX();
				lastY = e.getY();
				if (SwingUtilities.isLeftMouseButton(e))
				{
					if (stroke != null && tool == Tool.BRUSH)
					{
						paint(viewport.faceAt(e.getX(), e.getY()));
					}
				}
				else if (SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isRightMouseButton(e))
				{
					if (e.isShiftDown())
					{
						viewport.pan(dx, dy);
					}
					else
					{
						viewport.orbit(dx * 0.01, dy * 0.01);
					}
				}
				hover(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					endStroke();
				}
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				hover(e);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				viewport.setHoverFace(-1);
				showHelp();
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

	private void hover(MouseEvent e)
	{
		int face = viewport.faceAt(e.getX(), e.getY());
		viewport.setHoverFace(face);
		if (face >= 0)
		{
			status.setText("Face " + face + ": " + describe(document.color(face)));
		}
		else
		{
			showHelp();
		}
	}

	private void installKeys(JComponent root)
	{
		bind(root, "ctrl Z", "undo", this::undo);
		bind(root, "ctrl Y", "redo", this::redo);
		bind(root, "ctrl shift Z", "redo2", this::redo);
		bind(root, "ctrl S", "save", this::save);
		bind(root, "F", "frame", viewport::frame);
		bind(root, "B", "brush", () -> selectTool(root, Tool.BRUSH));
		bind(root, "G", "fill", () -> selectTool(root, Tool.FILL));
		bind(root, "I", "eyedropper", () -> selectTool(root, Tool.EYEDROPPER));
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

	private void selectTool(JComponent root, Tool value)
	{
		tool = value;
		selectToolButton(root, value);
	}

	private static void selectToolButton(Container container, Tool value)
	{
		for (Component child : container.getComponents())
		{
			if (child instanceof JToggleButton && ((JToggleButton) child).getClientProperty(Tool.class) == value)
			{
				((JToggleButton) child).setSelected(true);
			}
			else if (child instanceof Container)
			{
				selectToolButton((Container) child, value);
			}
		}
	}

	// --- Editing --------------------------------------------------------------------------------

	private void paint(int face)
	{
		if (face < 0 || stroke == null || document.color(face) == current)
		{
			return;
		}
		stroke.add(new Change(face, document.color(face), current));
		document.paint(face, current);
		viewport.relight();
		updateTitle();
	}

	/** Paints every visible face connected to {@code start} by edges that has its color. */
	private void fill(int start)
	{
		if (start < 0 || stroke == null)
		{
			return;
		}
		short from = document.color(start);
		if (from == current)
		{
			return;
		}

		List<int[]> adjacent = neighbours();
		boolean[] seen = new boolean[document.faceCount()];
		Deque<Integer> queue = new ArrayDeque<>();
		queue.add(start);
		seen[start] = true;
		while (!queue.isEmpty())
		{
			int face = queue.poll();
			stroke.add(new Change(face, from, current));
			document.paint(face, current);
			for (int next : adjacent.get(face))
			{
				if (!seen[next] && document.color(next) == from && !viewport.isHidden(next))
				{
					seen[next] = true;
					queue.add(next);
				}
			}
		}
		viewport.relight();
		updateTitle();
	}

	private List<int[]> neighbours()
	{
		if (neighbours != null)
		{
			return neighbours;
		}
		Mesh mesh = document.mesh();
		int faces = mesh.getFaceCount();
		Map<Long, List<Integer>> edges = new HashMap<>();
		for (int face = 0; face < faces; face++)
		{
			int[] v = {mesh.getFaceIndices1()[face], mesh.getFaceIndices2()[face], mesh.getFaceIndices3()[face]};
			for (int k = 0; k < 3; k++)
			{
				int a = Math.min(v[k], v[(k + 1) % 3]);
				int b = Math.max(v[k], v[(k + 1) % 3]);
				edges.computeIfAbsent((long) a << 32 | b, key -> new ArrayList<>()).add(face);
			}
		}

		List<List<Integer>> lists = new ArrayList<>();
		for (int face = 0; face < faces; face++)
		{
			lists.add(new ArrayList<>());
		}
		for (List<Integer> sharing : edges.values())
		{
			for (int a : sharing)
			{
				for (int b : sharing)
				{
					if (a != b)
					{
						lists.get(a).add(b);
					}
				}
			}
		}
		neighbours = new ArrayList<>();
		for (List<Integer> list : lists)
		{
			neighbours.add(list.stream().mapToInt(Integer::intValue).toArray());
		}
		return neighbours;
	}

	private void endStroke()
	{
		if (stroke != null && !stroke.isEmpty())
		{
			undo.push(stroke);
			redo.clear();
			addRecent(current);
			refreshModelColors();
		}
		stroke = null;
	}

	private void undo()
	{
		if (undo.isEmpty())
		{
			return;
		}
		List<Change> changes = undo.pop();
		for (int i = changes.size() - 1; i >= 0; i--)
		{
			document.paint(changes.get(i).face, changes.get(i).before);
		}
		redo.push(changes);
		afterHistory();
	}

	private void redo()
	{
		if (redo.isEmpty())
		{
			return;
		}
		List<Change> changes = redo.pop();
		for (Change change : changes)
		{
			document.paint(change.face, change.after);
		}
		undo.push(changes);
		afterHistory();
	}

	private void afterHistory()
	{
		viewport.relight();
		refreshModelColors();
		updateTitle();
	}

	// --- Colors ---------------------------------------------------------------------------------

	private void setCurrent(short hsl)
	{
		current = hsl;
		updatingSliders = true;
		hue.setValue(hsl >> 10 & 63);
		saturation.setValue(hsl >> 7 & 7);
		lightness.setValue(Math.max(RsColor.MIN_LUMINANCE, Math.min(RsColor.MAX_LUMINANCE, hsl & 127)));
		updatingSliders = false;
		showCurrent();
	}

	private void slidersChanged()
	{
		if (updatingSliders)
		{
			return;
		}
		current = (short) (hue.getValue() << 10 | saturation.getValue() << 7 | lightness.getValue());
		showCurrent();
	}

	private void showCurrent()
	{
		currentSwatch.setBackground(new Color(RsColor.hslToRgb(current & 0xFFFF)));
		currentLabel.setText(describe(current));
	}

	private static String describe(short hsl)
	{
		int value = hsl & 0xFFFF;
		return String.format("HSL %d  (hue %d, sat %d, light %d)  #%06X", value, value >> 10 & 63, value >> 7 & 7,
			value & 127, RsColor.hslToRgb(value));
	}

	private short mostUsedColor()
	{
		Map<Short, Integer> counts = colorCounts();
		return counts.isEmpty() ? (short) (32 << 10 | 4 << 7 | 64) : counts.keySet().iterator().next();
	}

	/** Colors by how many faces use them, most first. */
	private Map<Short, Integer> colorCounts()
	{
		Map<Short, Integer> counts = new HashMap<>();
		for (int face = 0; face < document.faceCount(); face++)
		{
			counts.merge(document.color(face), 1, Integer::sum);
		}
		Map<Short, Integer> sorted = new LinkedHashMap<>();
		counts.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
		return sorted;
	}

	private void refreshModelColors()
	{
		modelColors.removeAll();
		int shown = 0;
		for (short hsl : colorCounts().keySet())
		{
			if (shown++ == MAX_SWATCHES)
			{
				break;
			}
			modelColors.add(swatch(hsl));
		}
		modelColors.revalidate();
		modelColors.repaint();
	}

	private void addRecent(short hsl)
	{
		recent.remove((Short) hsl);
		recent.add(0, hsl);
		while (recent.size() > MAX_RECENT)
		{
			recent.remove(recent.size() - 1);
		}
		recentColors.removeAll();
		for (short color : recent)
		{
			recentColors.add(swatch(color));
		}
		recentColors.revalidate();
		recentColors.repaint();
	}

	// --- Files ----------------------------------------------------------------------------------

	private void updateTitle()
	{
		window.setTitle((document.isDirty() ? "* " : "") + path.getFileName() + " - Paint glTF");
	}

	private boolean save()
	{
		try
		{
			byte[] saved = document.save();
			Path backup = path.resolveSibling(path.getFileName() + ".bak");
			if (!Files.exists(backup))
			{
				Files.copy(path, backup);
			}
			Files.write(path, saved);

			// Continue from what is now on disk; face numbering is unchanged, so undo still applies
			GlbPaintDocument reloaded = GlbPaintDocument.load(saved);
			for (int face = 0; face < document.faceCount(); face++)
			{
				reloaded.paint(face, document.color(face));
			}
			document = reloaded;
			viewport.setDocument(reloaded);
			updateTitle();
			status.setText("Saved " + path.getFileName() + ". The file as it was before your first save is "
				+ backup.getFileName() + ".");
			return true;
		}
		catch (IOException | GltfException ex)
		{
			JOptionPane.showMessageDialog(window, "Could not save:\n" + ex.getMessage(), "Paint glTF",
				JOptionPane.ERROR_MESSAGE);
			return false;
		}
	}

	/** Whether it is fine to close: nothing unsaved, or the user saved or chose to discard it. */
	private boolean confirmDiscard()
	{
		if (!document.isDirty())
		{
			return true;
		}
		int choice = JOptionPane.showConfirmDialog(window, "Save your changes to " + path.getFileName() + "?",
			"Paint glTF", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice == JOptionPane.YES_OPTION)
		{
			return save();
		}
		return choice == JOptionPane.NO_OPTION;
	}
}
