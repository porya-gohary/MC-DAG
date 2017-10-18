/*******************************************************************************
 * Copyright (c) 2017 Roberto Medina
 * Written by Roberto Medina (rmedina@telecom-paristech.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package fr.tpt.s3.ls_mxc.alloc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import fr.tpt.s3.ls_mxc.model.Actor;
import fr.tpt.s3.ls_mxc.model.DAG;
import fr.tpt.s3.ls_mxc.model.Edge;
import fr.tpt.s3.ls_mxc.util.MathMCDAG;

public class MultiDAG {
	
	// Set of DAGs to be scheduled
	private Set<DAG> mcDags;
	
	// Architecture + Hyperperiod
	private int nbCores;
	private int hPeriod;
	
	// List of DAG nodes to order them
	private List<Actor> lLO;
	private List<Actor> lHI;
	private Comparator<Actor> lHIComp;
	private Comparator<Actor> lLOComp;
	
	// Scheduling tables
	private String sLO[][];
	private String sHI[][];
	
	// Virtual deadlines of HI tasks
	private Hashtable<Integer, String> virtualDeadline;

	// Remaining time for all nodes
	private Hashtable<String, Integer> remainTLO;
	private Hashtable<String, Integer> remainTHI;
	
	private boolean debug;
	
	/**
	 * Constructor of the Multi DAG scheduler
	 * @param sd
	 * @param cores
	 */
	public MultiDAG (Set<DAG> sd, int cores) {
		setMcDags(sd);
		setNbCores(cores);
		setVirtualDeadline(new Hashtable<>());
		remainTLO = new Hashtable<>();
		remainTHI = new Hashtable<>();
		
		lHIComp = new Comparator<Actor>() {
			@Override
			public int compare(Actor o1, Actor o2) {
				if (o1.getUrgencyHI() - o2.getUrgencyHI() != 0)
					return o1.getUrgencyHI() - o2.getUrgencyHI();
				else
					return o2.getId() - o1.getId();
			}			
		};
		
		lLOComp = new Comparator<Actor>() {
			@Override
			public int compare(Actor o1, Actor o2) {
				if (o1.getUrgencyLO() - o2.getUrgencyLO() != 0)
					return o1.getUrgencyLO() - o2.getUrgencyLO();
				else
					return o1.getId() - o2.getId();
			}		
		};
	}
	
	/**
	 * Allocates the scheduling tables & the virtual deadlines
	 */
	private void initTables () {
		int[] input = new int[getMcDags().size()];
		int i = 0;
	
		// Look for the maximum deadline
		for (DAG d : getMcDags()) {
			input[i] = d.getDeadline();
			i++;
		}
		
		sethPeriod(MathMCDAG.lcm(input));
		sHI = new String[gethPeriod()][getNbCores()];
		sLO = new String[gethPeriod()][getNbCores()];
		
		if (debug) System.out.println("DEBUG: initTables(): Hyper-period of the graph: "+gethPeriod()+"; tables initialized.");
	}
	
	/**
	 * Gives the LFT of an Actor
	 * @param a
	 * @param deadline
	 * @param mode
	 * @return
	 */
	private void calcActorLFTHI (Actor a, int deadline) {
		int ret = Integer.MAX_VALUE;
		
		if (a.isSource()) {
			ret = deadline;			
		} else {
			int test = Integer.MAX_VALUE;

			for (Edge e : a.getRcvEdges()) {
				test = e.getSrc().getLFTHI() - e.getSrc().getCHI();
				
				if (test < ret)
					ret = test;
			}
		}
		a.setLFTHI(ret);
	}
	
	private void calcActorLFTLO (Actor a, int deadline) {
		int ret = Integer.MAX_VALUE;
		
		if (a.isSink()) {			
			ret = deadline;
		} else {
			int test = Integer.MAX_VALUE;

			for (Edge e : a.getSndEdges()) {
				test = e.getDest().getLFTLO() - e.getDest().getCLO();
				if (test < ret)
					ret = test;
			}
		}
		a.setLFTLO(ret);
		a.setUrgencyLO(ret);
	}
	
	/**
	 * Recursively calculates LFTs of an actor
	 */
	private void calcVirtualDeadlines (DAG d) {
		// Add a list to add the nodes that have to be visited
		ArrayList<Actor> toVisit = new ArrayList<>();
		ArrayList<Actor> toVisitHI = new ArrayList<>();
		
		for (Actor a : d.getSinks()) {
			toVisit.add(a);
			a.setVisited(true);
		}
		
		for (Actor a : d.getSourcesHI()) {
			toVisitHI.add(a);
			a.setVisitedHI(true);
		}
		
		while (toVisit.size() != 0) {
			Actor a = toVisit.get(0);
			
			calcActorLFTLO(a, d.getDeadline());
			for (Edge e : a.getRcvEdges()) {
				if (!e.getSrc().isVisited()) {
					toVisit.add(e.getSrc());
					e.getSrc().setVisited(true);
				}
			}
			toVisit.remove(0);
		}
		
		while (toVisitHI.size() != 0) {
			Actor a = toVisitHI.get(0);
			
			calcActorLFTHI(a, d.getDeadline());
			for (Edge e : a.getSndEdges()) {
				if (e.getDest().getCHI() != 0 && !e.getDest().isVisitedHI()) {
					toVisitHI.add(e.getDest());
					e.getDest().setVisitedHI(true);
				}
			}
			toVisitHI.remove(0);
		}
	}
	
	/**
	 * Calculates weights for tasks depending on the deadline
	 */
	private void calcWeights () {
		for (DAG d : getMcDags()) {
			calcVirtualDeadlines(d);
		}
	}

	/**
	 * Checks for new activations in the HI mode for a given DAG
	 * @param a
	 * @param sched
	 */
	private void checkActorActivationHI (List<Actor> sched, List<Actor> ready) {
		// Check all predecessor of actor a that just finished
		for (Actor a : sched) {
			for (Edge e : a.getRcvEdges()) {
				Actor pred = e.getSrc();
				boolean add = true;
			
				// 	Check all successors of the predecessor
				for (Edge e2 : pred.getSndEdges()) {
					if (e2.getDest().getCHI() != 0 && !sched.contains(e2.getDest()))
						add = false;
				}
			
				if (add && !ready.contains(pred) && remainTHI.get(pred.getName()) != 0)
					ready.add(pred);					
			}
		}
	}
	
	/**
	 * Checks for activations in the LO mode
	 * @param a
	 * @param sched
	 */
	private void checkActorActivationLO (List<Actor> sched, List<Actor> ready) {
		// Check all predecessor of actor a that just finished
		for (Actor a : sched) {
			for (Edge e : a.getSndEdges()) {
				Actor succ = e.getDest();
				boolean add = true;
			
				// 	Check all successors of the predecessor
				for (Edge e2 : succ.getRcvEdges()) {
					if (!sched.contains(e2.getSrc()))
						add = false;
				}
			
				if (add && !ready.contains(succ) && remainTLO.get(succ.getName()) != 0)
					ready.add(succ);
			}
		}
	}
	
	/**
	 * Checks for the activation of a new DAG during the hyper-period
	 * @param slot
	 */
	private void checkDAGActivation (int slot, List<Actor> sched, List<Actor> lMode, short mode) {
		for (DAG d : getMcDags()) {
			// If the slot is a mulitple of the deadline there is a new activation
			if (slot % d.getDeadline() == 0) {
				ListIterator<Actor> it = sched.listIterator();
				
				if (isDebug()) System.out.println("DEBUG: checkDAGActivation(): DAG (id. "+d.getId()+") activation at slot "+slot);
				for (Actor a : d.getNodes()) {
					while (it.hasNext()) { // Remove nodes from the sched list
						Actor a2 = it.next();
						if (a.getName().contentEquals(a2.getName()))
							it.remove();
					}
					it = sched.listIterator();
					// Re-init remaining execution time to be allocated
					if (a.getCHI() != 0)
						remainTHI.put(a.getName(), a.getCHI());
					remainTLO.put(a.getName(), a.getCLO());
					
					if (mode == Actor.HI) {
						if (a.isSinkinHI())
							lMode.add(a);
					} else {
						if (a.isSource())
							lMode.add(a);
					}
					
					// Promote deadline on newly activated DAGs
					if (mode == Actor.LO)
						promoteVirtualDeadline(slot % d.getDeadline());
				}
			}
		}
	}
	
	/**
	 * Inits the remaining time to be allocated to each Actor
	 */
	private void initRemainT () {
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				if (a.getCHI() != 0)
					remainTHI.put(a.getName(), a.getCHI());
				remainTLO.put(a.getName(), a.getCLO());
			}
		}
	}
	
	/**
	 * Updates the laxity of each actor that is currently activated
	 * @param list
	 * @param slot
	 * @param mode
	 */
	private void calcLaxity (List<Actor> list, int slot, short mode) {
		for (Actor a : list) {
			int relatSlot = slot % a.getGraphDead();
					
			if (mode == Actor.HI) // Laxity in HI mode
				a.setUrgencyHI(a.getLFTHI() - relatSlot - remainTHI.get(a.getName()));
			else // Laxity in LO mode
				a.setUrgencyLO(a.getLFTLO() - relatSlot - remainTLO.get(a.getName()));
		}
	}
	
	/**
	 * Verifies if the scheduling table is worth computing
	 * @param slot
	 * @return
	 */
	private boolean isPossible (int slot, List<Actor> lMode, short mode) {
		int m = 0;
		boolean ret = true;
		ListIterator<Actor> it = lMode.listIterator();
		
		while (it.hasNext()) {
			if (mode == Actor.HI) {
				if (it.next().getUrgencyHI() == 0)
					m++;
			} else {
				if (it.next().getUrgencyLO() == 0)
					m++;
			}
			
			if (m > nbCores)
				ret = false;
		}
		
		return ret;
	}
	
	/**
	 * Allocates the DAGs in the HI mode and registers virtual deadlines
	 * @throws SchedulingException
	 */
	public void allocHI () throws SchedulingException {
		List<Actor> sched = new LinkedList<>();
		lHI = new ArrayList<Actor>();
		
		// Add all exit HI nodes to the ready list.
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				if (a.isSinkinHI())
					lHI.add(a);
			}
		}

		calcLaxity(lHI, 0, Actor.HI);
		lHI.sort(lHIComp);
		
		// Allocate all slots of the HI scheduling table
		ListIterator<Actor> lit = lHI.listIterator();
		boolean taskFinished = false;
		
		for (int s = hPeriod - 1; s >= 0; s--) {
			// Check if it's worth to continue the allocation
			if (!isPossible(s, lHI, Actor.HI)) {
				SchedulingException se = new SchedulingException("WARNING: allocHI() MultiDAG: Not enough slot left");
				throw se;
			}
			
			if (isDebug()) {
				System.out.print("DEBUG: allocHI(): @t = "+s+", tasks activated: ");
				for (Actor a : lHI)
					System.out.print("L("+a.getName()+") = "+a.getUrgencyHI()+"; ");
				System.out.println("");
			}
			
			for (int c = getNbCores() - 1; c >= 0; c--) {
				// Find a ready task in the HI list
				if (lit.hasNext()) {
					Actor a = lit.next();
					int val = remainTHI.get(a.getName());
					
					sHI[s][c] = a.getName();
					val--;
					
					// The HI part of the task has been allocated
					if (val == a.getCLO()) { 
						if (isDebug()) System.out.println("DEBUG: allocHI(): Adding virtual deadline @t = "+s+" for task "+a.getName());
						virtualDeadline.put((Integer)s, a.getName());
					}
					// The task has been fully scheduled
					if (val == 0) {
						sched.add(a);
						taskFinished = true;
						lit.remove();
					}
					remainTHI.put(a.getName(), val);
				}
			}
			
			if (taskFinished)
				checkActorActivationHI(sched, lHI);

			// Check if DAGs need to be activated at the next slot
			checkDAGActivation(s, sched, lHI, Actor.HI); 
			// Update laxities for nodes in the ready list
			calcLaxity(lHI, gethPeriod() - s, Actor.HI);
			lHI.sort(lHIComp);
			taskFinished = false;
			lit = lHI.listIterator();
		}
	}
	
	/**
	 * Promotes virtual deadlines of HI tasks once they have been allocated
	 * in HI criticality mode
	 */
	private void promoteVirtualDeadline (int slot) {
		for (DAG d : mcDags ) {
			for (Actor a : d.getNodes() ) {
				int ret = Integer.MAX_VALUE;
				
				if (a.getCHI() != 0) {
					ret =  a.getLFTLO();
					if (slot < ret)
						ret = slot;
				}
				a.setUrgencyLO(ret);
			}
		}
	}
	
	/**
	 * Allocates the DAGs in LO mode
	 * @throws SchedulingException
	 */
	public void allocLO () throws SchedulingException {
		List<Actor> sched = new LinkedList<>();
		lLO = new ArrayList<Actor>();
		
		// Add all HI nodes to the list.
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				if (a.isSource())
					lLO.add(a);
			}
		}
		
		// Upgrade LFTs for HI tasks
		
		
		calcLaxity(lLO, 0, Actor.LO);
		lLO.sort(lLOComp);
		
		// Allocate all slots of the LO scheduling table
		ListIterator<Actor> lit = lLO.listIterator();
		boolean taskFinished = false;
		
		for (int s = 0; s < hPeriod; s++) {
			// Verify that there are enough slots to continue the scheduling
			if (!isPossible(s, lLO, Actor.LO)) {
				SchedulingException se = new SchedulingException("WARNING: allocLO() MultiDAG: Not enough slot left");
				throw se;
			}
			
			if (isDebug()) {
				System.out.print("DEBUG: allocLO(): @t = "+s+", tasks activated: ");
				for (Actor a : lLO)
					System.out.print("L("+a.getName()+") = "+a.getUrgencyLO()+"; ");
				System.out.println("");
			}
			
			for (int c = 0; c < getNbCores(); c++) {
				// Find a ready task in the HI list
				if (lit.hasNext()) {
					Actor a = lit.next();
					int val = remainTLO.get(a.getName());
					
					sLO[s][c] = a.getName();
					val--;
					
					if (val == 0) {
						lit.remove();
						sched.add(a);
						taskFinished = true;
					}
					remainTLO.put(a.getName(), val);
				}
			}
			
			if (taskFinished)
				checkActorActivationLO(sched, lLO);
			
			checkDAGActivation(s + 1, sched, lLO, Actor.LO);
			calcLaxity(lLO, s + 1, Actor.LO);
			lLO.sort(lLOComp);
			taskFinished = false;
			lit = lLO.listIterator();
		}
	}
	
	/**
	 * Tries to allocate all DAGs in the number of cores given
	 * @param debug
	 * @throws SchedulingException
	 */
	public void allocAll (boolean debug) throws SchedulingException {
		this.setDebug(debug);
		initTables();
		calcWeights();
		
		if (isDebug()) printLFT();
		
		initRemainT();
		allocHI();
		if (isDebug()) printSHI();
		
		allocLO();
		if (isDebug()) printSLO();
	}
	
	/*
	 * Debugging functions
	 */
	/**
	 * Prints urgency values for the multi DAG scheduling
	 */
	public void printLFT () {
		for (DAG d : getMcDags()) {
			for (Actor a : d.getNodes()) {
				System.out.print("DEBUG: printLFT(): DAG "+d.getId()+"; Actor "+a.getName()
									+"; LFT LO "+a.getLFTLO());
				if (a.getCHI() != 0) System.out.print("; LFT HI "+a.getLFTHI());
				System.out.println(".");
			}
		}
		System.out.println("");
	}
	
	/**
	 * Prints the HI scheduling table
	 */
	public void printSHI () {
		for (int c = 0; c < getNbCores(); c++) {
			for (int s = 0; s < gethPeriod(); s++) {
				System.out.print(sHI[s][c]+" | ");
			}
			System.out.print("\n");
		}
		
		System.out.print("\n");
	}
	
	/**
	 * Prints the LO scheduling table
	 */
	public void printSLO () {
		for (int c = 0; c < getNbCores(); c++) {
			for (int s = 0; s < gethPeriod(); s++) {
				System.out.print(sLO[s][c]+" | ");
			}
			System.out.print("\n");
		}
	}
	
	/*
	 * Getters and setters
	 */

	public int getNbCores() {
		return nbCores;
	}

	public void setNbCores(int nbCores) {
		this.nbCores = nbCores;
	}

	public String[][] getsLO() {
		return sLO;
	}

	public void setsLO(String[][] sLO) {
		this.sLO = sLO;
	}

	public String[][] getsHI() {
		return sHI;
	}

	public void setsHI(String[][] sHI) {
		this.sHI = sHI;
	}

	public List<Actor> getlLO() {
		return lLO;
	}

	public void setlLO(List<Actor> lLO) {
		this.lLO = lLO;
	}

	public List<Actor> getlHI() {
		return lHI;
	}

	public void setlHI(List<Actor> lHI) {
		this.lHI = lHI;
	}

	public Set<DAG> getMcDags() {
		return mcDags;
	}

	public void setMcDags(Set<DAG> mcDags) {
		this.mcDags = mcDags;
	}

	public boolean isDebug() {
		return debug;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	public int gethPeriod() {
		return hPeriod;
	}

	public void sethPeriod(int hPeriod) {
		this.hPeriod = hPeriod;
	}

	public Comparator<Actor> getlLOComp() {
		return lLOComp;
	}

	public void setlLOComp(Comparator<Actor> lLOComp) {
		this.lLOComp = lLOComp;
	}

	public Comparator<Actor> getlHIComp() {
		return lHIComp;
	}

	public void setlHIComp(Comparator<Actor> lHIComp) {
		this.lHIComp = lHIComp;
	}

	public Hashtable<Integer, String> getVirtualDeadline() {
		return virtualDeadline;
	}

	public void setVirtualDeadline(Hashtable<Integer, String> virtualDeadline) {
		this.virtualDeadline = virtualDeadline;
	}
}
