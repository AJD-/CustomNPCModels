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
import com.customnpcmodels.inject.Rig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A joint hierarchy recovered from an engine rig, so an exported asset opens in Blender as an
 * armature an author can pose rather than a flat list of bones.
 * <p>
 * The engine has no parent/child relation: a transform that should carry a limb names every group
 * in it. In the canonical shape - a pivot naming one group, then a rotate naming that group's whole
 * subtree - those rotate sets form a laminar family (any two are disjoint or nested), and set
 * containment gives the tree back: a group's parent is whoever owns the next larger set around it.
 * <p>
 * The hierarchy is a convenience, not a correctness input. The writer animates each joint with
 * its group's exact per-frame transform and only uses the tree to express that relative to the
 * parent, so a rig that does not have the canonical shape falls back to a flat hierarchy - still
 * exact, just less pleasant to edit - and says so.
 */
final class JointTree
{
	/** One joint per vertex group with vertices, in parent-before-child order. */
	final int[] groups;

	/** Per joint, the index of its parent joint, or -1 for a root. */
	final int[] parents;

	/** Per joint, its rest position in engine units: the centroid of its group. */
	final double[][] restPositions;

	private JointTree(int[] groups, int[] parents, double[][] restPositions)
	{
		this.groups = groups;
		this.parents = parents;
		this.restPositions = restPositions;
	}

	int jointOfGroup(int group)
	{
		for (int joint = 0; joint < groups.length; joint++)
		{
			if (groups[joint] == group)
			{
				return joint;
			}
		}
		return -1;
	}

	static JointTree build(Mesh mesh, Rig rig, List<String> report)
	{
		List<Integer> used = new ArrayList<>();
		for (int group = 0; group < mesh.getVertexGroups().length; group++)
		{
			if (mesh.getVertexGroup(group).length > 0)
			{
				used.add(group);
			}
		}

		Map<Integer, Integer> parentGroup = rig == null ? null : hierarchy(rig, new TreeSet<>(used), report);
		if (parentGroup == null)
		{
			parentGroup = new TreeMap<>();
			for (int group : used)
			{
				parentGroup.put(group, -1);
			}
		}

		// Parent before child: repeatedly take every group whose parent is already placed
		List<Integer> order = new ArrayList<>();
		List<Integer> pending = new ArrayList<>(used);
		while (!pending.isEmpty())
		{
			boolean progressed = false;
			for (int i = 0; i < pending.size(); i++)
			{
				int group = pending.get(i);
				int parent = parentGroup.get(group);
				if (parent == -1 || order.contains(parent))
				{
					order.add(group);
					pending.remove(i--);
					progressed = true;
				}
			}
			if (!progressed)
			{
				report.add("The rig's hierarchy has a cycle through groups " + pending
					+ "; exporting a flat armature");
				return flat(mesh, used);
			}
		}

		int[] groups = order.stream().mapToInt(Integer::intValue).toArray();
		int[] parents = new int[groups.length];
		double[][] rest = new double[groups.length][];
		for (int joint = 0; joint < groups.length; joint++)
		{
			int parent = parentGroup.get(groups[joint]);
			parents[joint] = parent == -1 ? -1 : order.indexOf(parent);
			rest[joint] = centroid(mesh, groups[joint]);
		}
		return new JointTree(groups, parents, rest);
	}

	/**
	 * This tree with every joint whose parent is {@code unusable} hung from that parent's nearest
	 * usable ancestor instead, or made a root. Ancestors come first, so the order still holds.
	 */
	JointTree reparentedAround(boolean[] unusable)
	{
		int[] reparented = parents.clone();
		for (int joint = 0; joint < groups.length; joint++)
		{
			int parent = reparented[joint];
			while (parent != -1 && unusable[parent])
			{
				parent = parents[parent];
			}
			reparented[joint] = parent;
		}
		return new JointTree(groups, reparented, restPositions);
	}

	private static JointTree flat(Mesh mesh, List<Integer> used)
	{
		int[] groups = used.stream().mapToInt(Integer::intValue).toArray();
		int[] parents = new int[groups.length];
		Arrays.fill(parents, -1);
		double[][] rest = new double[groups.length][];
		for (int joint = 0; joint < groups.length; joint++)
		{
			rest[joint] = centroid(mesh, groups[joint]);
		}
		return new JointTree(groups, parents, rest);
	}

	/**
	 * Each used group's parent group (-1 for a root), or null when the rig does not have the laminar
	 * shape the hierarchy is read from.
	 */
	private static Map<Integer, Integer> hierarchy(Rig rig, TreeSet<Integer> used, List<String> report)
	{
		// Every rotate transform's set, restricted to groups the mesh has, with the one group its
		// pivot names when it names exactly one
		List<TreeSet<Integer>> sets = new ArrayList<>();
		List<Integer> owners = new ArrayList<>();
		int[] lastPivot = null;
		for (int transform = 0; transform < rig.getTransformCount(); transform++)
		{
			int type = rig.getType(transform);
			if (type == 0)
			{
				lastPivot = rig.getGroups(transform);
				continue;
			}
			if (type != 2)
			{
				continue;
			}

			TreeSet<Integer> set = new TreeSet<>();
			for (int group : rig.getGroups(transform))
			{
				if (used.contains(group))
				{
					set.add(group);
				}
			}
			if (set.isEmpty())
			{
				continue;
			}

			int owner = -1;
			if (lastPivot != null)
			{
				TreeSet<Integer> pivotGroups = new TreeSet<>();
				for (int group : lastPivot)
				{
					if (used.contains(group))
					{
						pivotGroups.add(group);
					}
				}
				if (pivotGroups.size() == 1)
				{
					owner = pivotGroups.first();
				}
			}

			int existing = sets.indexOf(set);
			if (existing == -1)
			{
				sets.add(set);
				owners.add(owner);
			}
			else if (owners.get(existing) == -1)
			{
				owners.set(existing, owner);
			}
		}

		for (int a = 0; a < sets.size(); a++)
		{
			for (int b = a + 1; b < sets.size(); b++)
			{
				TreeSet<Integer> x = sets.get(a);
				TreeSet<Integer> y = sets.get(b);
				boolean disjoint = x.stream().noneMatch(y::contains);
				if (!disjoint && !x.containsAll(y) && !y.containsAll(x))
				{
					report.add("Rig " + rig.getId() + " rotates overlapping group sets " + x + " and " + y
						+ " that are not nested; exporting a flat armature");
					return null;
				}
			}
		}

		List<Integer> bySize = new ArrayList<>();
		for (int i = 0; i < sets.size(); i++)
		{
			bySize.add(i);
		}
		bySize.sort(Comparator.comparingInt(i -> sets.get(i).size()));

		Map<Integer, Integer> parents = new TreeMap<>();
		for (int group : used)
		{
			// The group's own set is the smallest one it owns; failing that, just itself
			TreeSet<Integer> start = null;
			for (int i : bySize)
			{
				if (owners.get(i) == group && sets.get(i).contains(group))
				{
					start = sets.get(i);
					break;
				}
			}
			if (start == null)
			{
				start = new TreeSet<>();
				start.add(group);
			}

			int parent = -1;
			for (int i : bySize)
			{
				TreeSet<Integer> set = sets.get(i);
				int owner = owners.get(i);
				if (set.size() > start.size() && set.containsAll(start) && owner != -1 && owner != group)
				{
					parent = owner;
					break;
				}
			}
			parents.put(group, parent);
		}
		return parents;
	}

	private static double[] centroid(Mesh mesh, int group)
	{
		int[] members = mesh.getVertexGroup(group);
		double x = 0;
		double y = 0;
		double z = 0;
		for (int vertex : members)
		{
			x += mesh.getVerticesX()[vertex];
			y += mesh.getVerticesY()[vertex];
			z += mesh.getVerticesZ()[vertex];
		}
		int n = Math.max(members.length, 1);
		return new double[]{x / n, y / n, z / n};
	}
}
