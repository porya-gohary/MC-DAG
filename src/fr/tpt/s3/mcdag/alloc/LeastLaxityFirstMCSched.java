/*******************************************************************************
 * Copyright (c) 2018 Roberto Medina
 * Written by Roberto Medina (rmedina@telecom-paristech.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package fr.tpt.s3.mcdag.alloc;

import java.util.Comparator;
import java.util.Collections;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import fr.tpt.s3.mcdag.model.Actor;
import fr.tpt.s3.mcdag.model.ActorSched;
import fr.tpt.s3.mcdag.model.DAG;
import fr.tpt.s3.mcdag.model.Edge;
import fr.tpt.s3.mcdag.util.Counters;
import fr.tpt.s3.mcdag.util.MathMCDAG;

/**
 * Allocator of DAGs for N levels of criticality
 * @author roberto
 *
 */
public class LeastLaxityFirstMCSched extends AbstractMixedCriticalityScheduler {
	
	// Set of DAGs to be scheduled
	private Set<DAG> mcDags;
	
	// Architecture + hyperperiod + nb of levels
	private int nbCores;
	private int hPeriod;
	private int levels;
	
	private boolean inEquality;
	
	// Scheduling tables
	private String sched[][][];
	
	// Remaining time to be allocated for each node
	// Level, DAG id, Actor id
	private int remainingTime[][][];
	
	// Debugging boolean
	private boolean debug;
	
	// Comparators to order Actors
	private Comparator<ActorSched> loComp;
	private Comparator<ActorSched> eqComp;
	
	// Counter of ctx switches & preemptions per task
	private int activations;
	private Hashtable<ActorSched, Integer> ctxSwitch;
	private Hashtable<ActorSched, Integer> preempts;
		
	/**
	 * Constructor
	 * @param dags
	 * @param cores
	 * @param levels
	 * @param debug
	 */
	public LeastLaxityFirstMCSched (Set<DAG> dags, int cores, int levels, boolean debug) {
		setMcDags(dags);
		setNbCores(cores);
		setLevels(levels);
		setDebug(debug);
		remainingTime = new int[getLevels()][getMcDags().size()][];
		
		// Init remaining time for each DAG
		for (DAG d : getMcDags()) {
			for (int i = 0; i < getLevels(); i++) {
				remainingTime[i][d.getId()] = new int[d.getNodes().size()];
			}
		}
		
		setLoComp(new Comparator<ActorSched>() {
			@Override
			public int compare (ActorSched o1, ActorSched o2) {
				if (o1.getWeights()[0] - o2.getWeights()[0] != 0)
					return o1.getWeights()[0] - o2.getWeights()[0];
				else
					return o1.getId() - o2.getId();
			}
		});
	
		setCtxSwitch(new Hashtable<ActorSched, Integer>());
		setPreempts(new Hashtable<ActorSched, Integer>());
		setActivations(0);
	}
	
	/**
	 * Initializes the remaining time to be allocated for each node in each level
	 */
	private void initRemainTime () {
		for (int i = 0; i < getLevels(); i++) {
			for (DAG d : getMcDags()) {
				for (Actor a : d.getNodes()) {
					remainingTime[i][d.getId()][a.getId()] = a.getWcet(i);
				}	
			}
		}
	}
	
	/**
	 * Inits the scheduling tables and calculates the hyper-period
	 */
	protected void initTables () {
		int[] input = new int[getMcDags().size()];
		int i = 0;
		
		for (DAG d : getMcDags()) {
			input[i] = d.getDeadline();
			i++;
		}
		
		sethPeriod(MathMCDAG.lcm(input));
		
		// Init scheduling tables
		sched = new String[getLevels()][gethPeriod()][getNbCores()];
		
		for (i = 0; i < getLevels(); i++) {
			for (int j = 0; j < gethPeriod(); j++) {
				for (int k = 0; k < getNbCores(); k++) {
					sched[i][j][k] = "-";
				}
			}
		}
		
		if (debug) System.out.println("[DEBUG "+Thread.currentThread().getName()+"] initTables(): Sched tables initialized!");
		
		// Calc number of activations
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				// Check if task runs in HI mode
				int nbActivations = (int) (hPeriod / d.getDeadline());
				if (a.getWcet(1) != 0)
					activations = activations + nbActivations * d.getLevels();
				else
					activations = activations + nbActivations;
			}
		}
	}
	
	
	/**
	 * Checks how many slots have been allocated for a in l mode until
	 * time slot t.
	 * @param a
	 * @param t
	 * @param l
	 * @return
	 */
	private int scheduledUntilTinL (ActorSched a, int t, int l) {
		int ret = 0;
		int start = (int)(t / a.getGraphDead()) * a.getGraphDead();
		
		for (int i = start; i <= t; i++) {
			for (int c = 0; c < nbCores; c++) {
				if (sched[l][i][c] !=  null) {
					if (sched[l][i][c].contentEquals(a.getName()))
						ret++;
				}
			}
		}
		
		return ret;
	}
	
	/**
	 * Checks how many slots have been allocated for a in l mode in reverse
	 * from the deadline until the current slot
	 * @param a
	 * @param t
	 * @param l
	 * @return
	 */
	private int scheduledUntilTinLreverse (ActorSched a, int t, int l) {
		int ret = 0;
		int end = 0;
		
		int realSlot = gethPeriod() - t;
		
		if (t == 0)
			return 0;
		
		if ((int)(realSlot/a.getGraphDead()) <= 0 || realSlot % a.getGraphDead() == 0) {
			end = a.getGraphDead() - 1;
		} else {
			end = ((int)(realSlot / a.getGraphDead()) + 1)  * a.getGraphDead() - 1;
		}
		
		//System.out.println("\t\t\t [schedut] task "+a.getName()+" end "+end+" slot "+realSlot);
		
		for (int i = end; i > realSlot; i--) {
			for (int c = 0; c < nbCores; c++) {
				if (sched[l][i][c] !=  null) {
					if (sched[l][i][c].contentEquals(a.getName()))
						ret++;
				}
			}
		}
		return ret;
	}
	
	/**
	 * Resets temporary delays of HI tasks
	 */
	@SuppressWarnings("unused")
	private void resetDelays() {
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				if (a.getWcet(1) != 0)
					((ActorSched) a).setDelayed(false);
			}
		}
	}
	
	/**
	 * Calculates the laxity of the ready tasks that are on the list
	 * @param list
	 * @param slot
	 * @param level
	 */
	private void calcLaxity(List<ActorSched> list, int slot, int level) {
		for (ActorSched a : list) {
			int relatSlot = slot % a.getGraphDead();
			int dId = a.getGraphID();
			
			// The laxity has to be calculated for a HI mode
			if (level >= 1) {

				// It's not the highest criticality level -> perform checks
				if (level != getLevels() - 1) {
					int deltaI = a.getWcet(level + 1) - a.getWcet(level);
					//Check if in the higher table the Ci(L+1) - Ci(L) has been allocated
					if (scheduledUntilTinLreverse(a, slot, level + 1) - deltaI < 0) {
						if (isDebug()) System.out.println("[DEBUG "+Thread.currentThread().getName()+"] calcLaxity(): Task "+a.getName()+" needs to be delayed at slot @t = "+slot);
						a.setWeightInL(Integer.MAX_VALUE, level);
					} else if (scheduledUntilTinLreverse(a, slot, level) != 0 &&
							scheduledUntilTinLreverse(a, slot, level) - scheduledUntilTinLreverse(a, slot, level + 1) + deltaI == 0) {
						if (isDebug()) System.out.println("[DEBUG "+Thread.currentThread().getName()+"] calcLaxity(): Task "+a.getName()+" needs to be delayed at slot @t = "+slot);
						a.setWeightInL(Integer.MAX_VALUE, level);
					} else {
						a.setWeightInL(a.getDeadlines()[level] - relatSlot - remainingTime[level][dId][a.getId()], level);
					}
				} else {
					a.setWeightInL(a.getDeadlines()[level] - relatSlot - remainingTime[level][dId][a.getId()], level);
				}
			// Laxity in LO mode
			} else {
				// If it's a HI task
				if (a.getWcet(level + 1) > 0) {
					// Promotion needed for the task
					if ((a.getWcet(level) - remainingTime[level][dId][a.getId()]) - scheduledUntilTinL(a, slot, level + 1) < 0) {
						if (isDebug()) System.out.println("[DEBUG "+Thread.currentThread().getName()+"] calcLaxity(): Promotion of task "+a.getName()+" at slot @t = "+slot);
						a.setWeightInL(0, level);
					} else {
						a.setWeightInL(a.getDeadlines()[level] - relatSlot - remainingTime[level][dId][a.getId()], level);
					}
				} else {
					a.setWeightInL(a.getDeadlines()[level] - relatSlot - remainingTime[level][dId][a.getId()], level);
				}
			}
		}
	}
	
	/**
	 * Method to prevent preemptions when tasks have the same laxity
	 * the equality is only interesting on the last element m of the list
	 * where m is the number of available cores
	 * @param ready
	 */
	@SuppressWarnings("unused")
	private void checkForEqualities (List<ActorSched> ready, final int level) {
		
		// There are enough elements in the ready list to test
		// for equalities
		if (ready.size() > getNbCores()) {
			int nbTasksEqualityinReady = 1;
			int eqLax = ready.get(getNbCores() - 1).getWeights()[level];
			int index = getNbCores() - 2;
			int count = 0;	// nb of tasks with same laxity already in the ready queue
			boolean eq = (ready.get(getNbCores() - 2).getWeights()[level] == eqLax) ? true : false;
			List<ActorSched> eqList = new LinkedList<ActorSched>();

			// Check nodes before the last schedulable element
			while (eq && index >= 0) {
				nbTasksEqualityinReady++;
				eqList.add(ready.get(index));
				index--;
				if (index > 0)
					eq = (ready.get(index).getWeights()[level] == eqLax) ? true : false;
				else
					eq = false;
			}
			
			count = nbTasksEqualityinReady;
			
			eq = (ready.get(getNbCores()).getWeights()[level] == eqLax) ? true : false;
			index = getNbCores() - 1;
			// Check nodes after the last element
		
			while (eq) {
				eqList.add(ready.get(index));
				index++;
				if (index < ready.size())
					eq = (ready.get(index).getWeights()[level] == eqLax) ? true : false;
				else
					eq = false;
			}
			
			boolean list = false;
			
			if (eqList.size() != 0)
				list = true;
			
			// Sort equality list
			if (list) {
				if (isDebug()) System.out.println("[DEBUG "+Thread.currentThread().getName()+"] checkForEqualities(): Equalities of laxities");
				Collections.sort(eqList, new Comparator<ActorSched>() {
					@Override
					public int compare (ActorSched o1, ActorSched o2) {
						if (o1.getDeadlines()[level] - o2.getDeadlines()[level] != 0)
							return o1.getDeadlines()[level] - o2.getDeadlines()[level];
						else
							return o1.getId() - o2.getId();
					}
				});
				
				if (isDebug()) {
					System.out.print("[DEBUG "+Thread.currentThread().getName()+"] checkForEqualities(): tasks in equality: ");
					for (ActorSched a : eqList)
						System.out.print(a.getName()+" Laxity "+a.getWeights()[level]+"; ");
					System.out.println("");
				}
				
				// Update delayed booleans
				int remain = count - 1;
				index = getNbCores() - count;
				
				while (remain > 0) {
					ActorSched a = ready.get(index);
					
					for (ActorSched eqAct : eqList) {
						if (a.equals(eqAct)) {
							remain--;
						} else {
							a.setDelayed(true);
						}
					}
					index++;
				}
			}
		}
	}
	
	/**
	 * Functions that verifies if computing the scheduling table is worth it
	 * @param list
	 * @param slot
	 * @param level
	 * @return
	 */
	private boolean isPossible (List<ActorSched> list, int slot, int level) {
		int m = 0;
		ListIterator<ActorSched> lit = list.listIterator();
		
		while (lit.hasNext()) {
			ActorSched a = lit.next();
			
			if (a.getWeights()[level] == 0)
				m++;
			else if (a.getWeights()[level] < 0)
				return false;
			
			if (m > nbCores)
				return false;
		}
		
		return true;
	}
	
	/**
	 * Checks for new activations in HI modes
	 * @param sched
	 * @param ready
	 * @param level
	 */
	private void checkActivationHI (List<ActorSched> sched, List<ActorSched> ready, int level) {

		for (ActorSched a : sched) {
			// Check predecessors of task that was just allocated
			for (Edge e : a.getRcvEdges()) {
				ActorSched pred = (ActorSched) e.getSrc();
				boolean add = true;
				
				// Check if all successors of the predecessor have been allocated
				for (Edge e2 : pred.getSndEdges()) {
					if (e2.getDest().getWcet(level) > 0 && !sched.contains(e2.getDest())) {
						add = false;
						break;
					}
				}
				
				if (add && !ready.contains(pred) && remainingTime[level][pred.getGraphID()][pred.getId()] != 0) {
					ready.add(pred);
				}
			}
		}
	}
	
	
	/**
	 * Checks for new activations in the LO mode
	 * @param sched
	 * @param ready
	 * @param level
	 */
	private void checkActivationLO (List<ActorSched> sched, List<ActorSched> ready) {

		for (ActorSched a : sched) {
			// Check predecessors of task that was just allocated
			for (Edge e : a.getSndEdges()) {
				ActorSched succ = (ActorSched) e.getDest();
				boolean add = true;
				
				// Check if all successors of the predecessor have been allocated
				for (Edge e2 : succ.getRcvEdges()) {
					if (!sched.contains(e2.getSrc())) {
						add = false;
						break;
					}
				}
				
				if (add && !ready.contains(succ) && remainingTime[0][succ.getGraphID()][succ.getId()] != 0) {
					ready.add(succ);
				}
			}
		}
	}
	
	/**
	 * Checks for new activations of DAGs
	 * @param sched
	 * @param ready
	 * @param slot
	 * @param level
	 */
	private void checkDAGActivation (List<ActorSched> sched, List<ActorSched> ready, int slot, int level) {
		for (DAG d : getMcDags()) {
			// If the slot is a multiple of the deadline is a new activation
			if (slot % d.getDeadline() == 0) {
				ListIterator<ActorSched> it = sched.listIterator();
				
				if (isDebug()) System.out.println("[DEBUG "+Thread.currentThread().getName()+"] checkDAGActivation(): DAG (id. "+d.getId()+") activation at slot "+slot);
				for (Actor a : d.getNodes()) {
					while (it.hasNext()) { // Remove nodes from the sched list
						ActorSched a2 = it.next();
						if (a.getName().contentEquals(a2.getName()))
							it.remove();
					}
					it = sched.listIterator();
					// Re-init execution time
					remainingTime[level][((ActorSched)a).getGraphID()][a.getId()] = a.getWcet(level);
					
					if (level >= 1 && a.isSinkinL(level)) {
						ready.add((ActorSched)a);
					} else if (level == 0 && a.isSourceinL(level)) {
						ready.add((ActorSched)a);
					}
				}
			}
		}
	}
	
	/**
	 * Builds the scheduling table of level l
	 * @param l
	 * @throws SchedulingException
	 */
	private void buildHITable (final int l) throws SchedulingException {
		List<ActorSched> ready = new LinkedList<>();
		List<ActorSched> scheduled = new LinkedList<>();
		
		// Add all sink nodes
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				if (a.isSinkinL(l))
					ready.add((ActorSched) a);
			}
		}
		
		calcLaxity(ready, 0, l);
		Collections.sort(ready, new Comparator<ActorSched>() {
			@Override
			public int compare(ActorSched o1, ActorSched o2) {
				if (o1.getWeights()[l] - o2.getWeights()[l] != 0)
					return o1.getWeights()[l] - o2.getWeights()[l];
				else
					return o2.getId() - o1.getId();
			}
		});
		
		// Allocate slot by slot the HI scheduling tables
		ListIterator<ActorSched> lit = ready.listIterator();
		boolean taskFinished = false;
		
		for (int s = hPeriod - 1; s >= 0; s--) {
			if (isDebug()) {
				System.out.print("[DEBUG "+Thread.currentThread().getName()+"] buildHITable("+l+"): @t = "+s+", tasks activated: ");
				for (ActorSched a : ready)
					System.out.print("L("+a.getName()+") = "+a.getWeights()[l]+"; ");
				System.out.println("");
			}
			
			// Check if it's worth to continue the allocation
			if (!isPossible(ready, s, l)) {
				SchedulingException se = new SchedulingException("[ERROR "+Thread.currentThread().getName()+"] buildHITable("+l+"): Not enough slots left.");
				throw se;
			}
			
			for (int c = getNbCores() - 1; c >=0; c--) {
				// Find a top ready task
				if (lit.hasNext()) {
					ActorSched a = lit.next();
					if (a.getWeights()[l] == Integer.MAX_VALUE)
						break;
					int val = remainingTime[l][a.getGraphID()][a.getId()];
					
					sched[l][s][c] = a.getName();
					val--;
					
					// The task has been fully scheduled
					if (val == 0) {
						scheduled.add(a);
						taskFinished = true;
						lit.remove();
					}
					remainingTime[l][a.getGraphID()][a.getId()] = val;
				}
			}
			
			// resetDelays();
			
			// It a task has been fully allocated check for new activations
			if (taskFinished)
				checkActivationHI(scheduled, ready, l);
			
			// Check for new DAG activations
			if (s != 0) {
				checkDAGActivation(scheduled, ready, s, l);
				// Update laxities for nodes
				calcLaxity(ready, gethPeriod() - s, l);
			}

			
			Collections.sort(ready, new Comparator<ActorSched>() {
				@Override
				public int compare(ActorSched o1, ActorSched o2) {
					if (o1.getWeights()[l] - o2.getWeights()[l] != 0)
						return o1.getWeights()[l] - o2.getWeights()[l];
					else
						return o2.getId() - o1.getId();
				}
			});
			taskFinished = false;
			lit = ready.listIterator();
		}
		if (!ready.isEmpty()) {
			SchedulingException se = new SchedulingException("[ERROR "+Thread.currentThread().getName()+"] buildLOTable(0): Ready list not empty.");
			throw se;
		}
	}
	
	/**
	 * Builds the LO scheduling table
	 * @throws SchedulingException
	 */
	private void buildLOTable () throws SchedulingException {
		List<ActorSched> ready = new LinkedList<>();
		List<ActorSched> scheduled = new LinkedList<>();
		
		// Add all source nodes
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				if (a.isSourceinL(0)) {
					ready.add((ActorSched) a);
				}
			}
		}
		
		calcLaxity(ready, 0, 0);
		Collections.sort(ready, loComp);
		//checkForEqualities(ready, 0);
		
		// Allocate slot by slot the scheduling table
		ListIterator<ActorSched> lit = ready.listIterator();
		boolean taskFinished = false;
		
		for (int s = 0; s < gethPeriod(); s++) {
			if (isDebug()) {
				System.out.print("[DEBUG "+Thread.currentThread().getName()+"] buildLOTable(0): @t = "+s+", tasks activated: ");
				for (ActorSched a : ready)
					System.out.print("L("+a.getName()+") = "+a.getWeights()[0]+"; ");
				System.out.println("");
			}
			
			// Verify that is still worth trying to compute the sched table
			if (!isPossible(ready, s, 0)) {
				SchedulingException se = new SchedulingException("[ERROR "+Thread.currentThread().getName()+"] buildLOTable(0): Not enough slots left.");
				throw se;
			}
			
			for (int c = 0; c < getNbCores(); c++) {
				// Get the next element on the LO list
				if (lit.hasNext()) {
					ActorSched a = lit.next();
					int val = remainingTime[0][a.getGraphID()][a.getId()];
					
					sched[0][s][c] = a.getName();
					val--;
					
					if (val == 0) {
						scheduled.add(a);
						taskFinished = true;
						lit.remove();
					}
					remainingTime[0][a.getGraphID()][a.getId()] = val;
				}
			}
			
			if (taskFinished)
				checkActivationLO(scheduled, ready);
			
			if (s != hPeriod - 1) {
				checkDAGActivation(scheduled, ready, s + 1, 0);
				calcLaxity(ready, s + 1, 0);
			}
			Collections.sort(ready, loComp);
			//checkForEqualities(ready, 0);
			taskFinished = false;
			lit = ready.listIterator();
		}
		if (!ready.isEmpty()) {
			SchedulingException se = new SchedulingException("[ERROR "+Thread.currentThread().getName()+"] buildLOTable(0): Ready list not empty.");
			throw se;
		}
	}
	
	/**
	 * Builds all the scheduling tables for the system
	 */
	public void buildAllTables () throws SchedulingException {
		initRemainTime();
		initTables();
		
		// Calculate LFTs and urgencies in all DAGs
		for (DAG d : getMcDags()) {
			calcDeadlines(d, getLevels());
			if (isDebug()) printLFTs(d);
		}
		
		// Build tables: more critical tables first
		for (int i = getLevels() - 1; i >= 1; i--)			
			buildHITable(i);

		buildLOTable();		
		
		/*for (int i = 0; i < getLevels(); i++)
			AlignScheduler.align(sched, i, gethPeriod(), getNbCores());*/
		
		if (isDebug()) printTables();
		
		// Count preemptions
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				preempts.put((ActorSched)a, 0);
			}
		}
		
		// Counters.countContextSwitch(sched, ctxSwitch, getLevels(), hPeriod, nbCores);
		Counters.countPreemptions(sched, preempts, getLevels(), hPeriod, nbCores);
		
		if (isDebug()) printPreempts();
	}
	
	/*
	 * DEBUG functions
	 */
	
	/**
	 * Prints LFTs for all DAGs and all nodes in all the levels
	 * @param d
	 */
	private void printLFTs (DAG d) {
		System.out.println("[DEBUG "+Thread.currentThread().getName()+"] DAG "+d.getId()+" printing LFTs");
		
		for (Actor a : d.getNodes()) {
			System.out.print("[DEBUG "+Thread.currentThread().getName()+"]\t Actor "+a.getName()+", ");
			for (int i = 0; i < getLevels(); i++) {
				if (((ActorSched)a).getDeadlines()[i] != Integer.MAX_VALUE)
					System.out.print(((ActorSched)a).getDeadlines()[i]);
				System.out.print(" ");
			}
			System.out.println("");
		}
	}
	
	/**
	 * Prints information about the DAGs
	 */
	public void printDAGs () {
		System.out.println("[DEBUG "+Thread.currentThread().getName()+"] N levels: Number of DAGs "+getMcDags().size()+", on "+getLevels()+" levels.");
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				System.out.print("[DEBUG "+Thread.currentThread().getName()+"]\t Actor "+a.getName()+", ");
				for (int i = 0; i < getLevels(); i++)
					System.out.print(a.getWcet(i)+" ");
				System.out.println("");
				
				for (Edge e : a.getSndEdges())
					System.out.println("[DEBUG "+Thread.currentThread().getName()+"]\t\t Edge "+e.getSrc().getName()+" -> "+e.getDest().getName());
			}
		}
	}
	
	/**
	 * Prints the scheduling tables
	 */
	public void printTables () {
		for (int i = getLevels() - 1; i >= 0; i--) {
			System.out.println("Scheduling table in mode "+ i+":");
			for (int c = 0; c < getNbCores(); c++) {
				for (int s = 0; s < gethPeriod(); s++) {
					if (sched[i][s][c] != null)
						System.out.print(sched[i][s][c]+" | ");
					else
						System.out.print("-- | ");
				}
				System.out.print("\n");
			}
		}
		System.out.print("\n");
	}
	
	public void printPreempts () {
		int total = 0;
		System.out.println("[DEBUG "+Thread.currentThread().getName()+"] Printing preemption data...");

		for (ActorSched a : preempts.keySet()) {
			System.out.println("[DEBUG "+Thread.currentThread().getName()+"]\t Task "+a.getName()+" peempted "+preempts.get(a)+" times.");
			total += preempts.get(a);
		}
		System.out.println("[DEBUG "+Thread.currentThread().getName()+"] Total number of preemptions = "+total+" for "+getActivations()+" activations");
	}
	
	/*
	 * Getters & Setters
	 */
	
	public Set<DAG> getMcDags() {
		return mcDags;
	}

	public void setMcDags(Set<DAG> mcDags) {
		this.mcDags = mcDags;
	}

	public int getNbCores() {
		return nbCores;
	}

	public void setNbCores(int nbCores) {
		this.nbCores = nbCores;
	}

	public int gethPeriod() {
		return hPeriod;
	}

	public void sethPeriod(int hPeriod) {
		this.hPeriod = hPeriod;
	}

	public int getLevels() {
		return levels;
	}

	public void setLevels(int levels) {
		this.levels = levels;
	}

	public String[][][] getSched() {
		return sched;
	}

	public void setSched(String[][][] sched) {
		this.sched = sched;
	}

	public boolean isDebug() {
		return debug;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	public int[][][] getRemainingTime() {
		return remainingTime;
	}

	public void setRemainingTime(int remainingTime[][][]) {
		this.remainingTime = remainingTime;
	}

	public Comparator<ActorSched> getLoComp() {
		return loComp;
	}

	public void setLoComp(Comparator<ActorSched> loComp) {
		this.loComp = loComp;
	}

	public Hashtable<ActorSched, Integer> getPreempts() {
		return preempts;
	}

	public void setPreempts(Hashtable<ActorSched, Integer> preempts) {
		this.preempts = preempts;
	}

	public Hashtable<ActorSched, Integer> getCtxSwitch() {
		return ctxSwitch;
	}

	public void setCtxSwitch(Hashtable<ActorSched, Integer> ctxSwitch) {
		this.ctxSwitch = ctxSwitch;
	}

	public int getActivations() {
		return activations;
	}

	public void setActivations(int activations) {
		this.activations = activations;
	}

	public Comparator<ActorSched> getEqComp() {
		return eqComp;
	}

	public void setEqComp(Comparator<ActorSched> eqComp) {
		this.eqComp = eqComp;
	}

	public boolean isInEquality() {
		return inEquality;
	}

	public void setInEquality(boolean inEquality) {
		this.inEquality = inEquality;
	}

}
