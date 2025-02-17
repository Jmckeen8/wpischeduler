package edu.wpi.scheduler.client.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;

import com.google.gwt.event.shared.EventHandler;

import edu.wpi.scheduler.client.controller.SchedulePermutation;
import edu.wpi.scheduler.client.controller.SectionProducer;
import edu.wpi.scheduler.client.controller.StudentSchedule;
import edu.wpi.scheduler.client.generator.ProducerUpdateEvent.UpdateType;
import edu.wpi.scheduler.client.permutation.PermutationController;
import edu.wpi.scheduler.shared.model.DayOfWeek;
import edu.wpi.scheduler.shared.model.Period;
import edu.wpi.scheduler.shared.model.Section;
import edu.wpi.scheduler.shared.model.Term;
import edu.wpi.scheduler.shared.model.Time;
import edu.wpi.scheduler.shared.model.TimeCell;

/**
 * Finds all conflicts between periods, and courses schedules.
 * 
 * The procedure works as follows: -Place all the sections in a tree, where each
 * layer of the tree are all sections of a given course (exclude any courses
 * with 0 sections) -Traverse the tree using DFS -Before adding each item of to
 * the DFS, compare the item to all the elements in the DFS's queue, and check
 * for conflicts. -When we reach the end of the tree. (A section of each course
 * is in the queue), and there are no conflicts, create a new schedule.
 * 
 * @author Nican
 * 
 */
public class ScheduleProducer {

	public static interface ProducerEventHandler extends EventHandler {
		public void onPermutationUpdated(UpdateType type);
	}

	private final StudentSchedule controller;
	
	/**
	 * List of sections that we are going to cross match to find conflicts and
	 * schedules
	 */
	public final ArrayList<List<Section>> courses;
	
	/**
	 * List of actual found schedule permutations
	 */
	private List<SchedulePermutation> permutations = new ArrayList<SchedulePermutation>();
	
	public final Stack<SearchState> searchStack = new Stack<SearchState>();
	
	public int maxSolutions = 0;
	
	public class SearchState {	
		public final List<Section> sections;
		public final List<AbstractProblem> solutions;
		int currentCourse = 0;
		int currentSection = 0;
		
		public SearchState(){
			sections = new ArrayList<Section>();
			solutions = new ArrayList<AbstractProblem>();	
		}
		
		public SearchState(SearchState other){
			sections = new ArrayList<Section>(other.sections);
			solutions = new ArrayList<AbstractProblem>(other.solutions);
			currentCourse = other.currentCourse;
			currentSection = other.currentSection;		
		}
		
		public List<Section> getCurrentCourse(){
			return courses.get(currentCourse);
		}
		
		public Section getCurrentSection(){
			return getCurrentCourse().get(currentSection);
		}
		
		public boolean hasNextCourse(){
			return currentCourse < courses.size() - 1;
		}
		
		public boolean hasNextSection(){
			return currentSection < getCurrentCourse().size() - 1;
		}
		
		public boolean canAddSolutions(){
			return solutions.size() < maxSolutions;
		}
	}

	public ScheduleProducer(PermutationController controller) {
		this.controller = controller.studentSchedule;
		this.courses = new ArrayList<List<Section>>();		

		for (SectionProducer producer : this.controller.sectionProducers) {
			List<Section> producerSections = producer.getSections();

			if (!producerSections.isEmpty())
				courses.add(producerSections);
		}

		Collections.sort(courses, new Comparator<List<Section>>() {

			@Override
			public int compare(List<Section> o1, List<Section> o2) {
				int s1 = o1.size();
				int s2 = o2.size();

				if (s1 == s2)
					return 0;
				return s1 < s2 ? -1 : 1;
			}
		});

		addInitialState();
	}
	
	public ScheduleProducer(ScheduleProducer producer) {
		this.controller = producer.controller;
		this.courses = new ArrayList<List<Section>>(producer.courses);		
		
		addInitialState();
	}	
	
	private void addInitialState(){
		//Well then, there is nothing to do here.
		if( courses.isEmpty() )
			return;
				
		SearchState state = new SearchState();
		searchStack.push(state);
	}
	
	public List<List<Section>> getCourses(){
		return courses;
	}

	public List<SchedulePermutation> getPermutations() {
		return permutations;
	}

	public boolean canGenerate() {
		return !searchStack.isEmpty();
	}

	public void step(){		
		//Find a section we can add, on the current course.
		SearchState state = searchStack.pop();
		
		if( state.hasNextSection() ){
			SearchState newState = new SearchState(state);
			newState.currentSection++;
			searchStack.push(newState);			
		}
		
		findNextSection(state);
	}
	
	public void findNextSection( SearchState state ){
		
		Section section = state.getCurrentSection();
		
		//First to check if we can add the section due to time conflict.
		if(hasTimeConflicts(section)){
			// push solutions
			if(state.canAddSolutions())
			{
				pushTimeConflictSolutions(state, section);
			}
			return;
		}
		//Then check for conflicts with current sections
		if(hasConflicts(state.sections, section)){
			if(state.canAddSolutions()){
				pushConflictSolutions(state, section);				
			}			
			return;
		}
		
		//We can add the section to the state, since there are no problems.
		SearchState newState = new SearchState(state);
		newState.sections.add(section);		
		
		addNewState( newState );
	}
	
	private boolean hasTimeConflicts(Section section) 
	{
		HashMap<Term, List<TimeCell>> conflicts = getTimeConflicts(section);
		List<Term> offeredTerms = section.getTerms();
		// For each term
		for(Term t : offeredTerms)
		{
			if(!(conflicts.get(t).isEmpty()))
			{
				return true;
			}
		}
		return false;
	}

	private void pushConflictSolutions(SearchState state, Section newSection) {
		//There are two courses in conflict, we can do two things:
		//1. Remove the course that we are trying to add
		//2. Remove the course that is already added.
		
		for( Section section : state.sections ){
			if( controller.conflicts.hasConflicts(newSection, section)){
				
				SearchState newState = new SearchState(state);
				newState.solutions.add(new ConflictProblem(newSection, section));
				//Here we do not add the newSection
				
				SearchState newState2 = new SearchState(state);
				newState2.solutions.add(new ConflictProblem(section, newSection));
				//Here, we remove the conflicting section, and add the new one.
				newState2.sections.remove(section);
				newState2.sections.add(newSection);
				
				addNewState(newState);
				addNewState(newState2);
			}
		}
	}
	
	private void pushTimeConflictSolutions(SearchState state, Section section) 
	{
		SearchState newState = new SearchState(state);
		newState.solutions.add(new TimeConflictProblem(this, section));
		newState.sections.add(section);
		
		//If there is a conflict, even after adding the time
		//We are going to need an extra solution to fix the course conflict
		if(hasConflicts(state.sections, section)){
			if(!newState.canAddSolutions())
				return;
			
			pushConflictSolutions(newState, section);
			return;
		}
		
		addNewState(newState);
	}
	
	private void addNewState( SearchState newState ){
		if(newState.hasNextCourse()){
			newState.currentCourse++;
			newState.currentSection = 0;
			
			searchStack.push(newState);
		} else {
			//We reached the end of the tree, and therefore we have a a schedule! :D
			SchedulePermutation permutation = new SchedulePermutation(newState);
			
			//Remove all items that require the same solution
			if( newState.solutions.size() > 0 ){
				while(!searchStack.isEmpty()){
					SearchState state = searchStack.peek();
					
					if(!state.solutions.equals(newState.solutions))
						break;
					
					searchStack.pop();			
				}
			}
			
			permutations.add(permutation);
		}
	}

	public boolean hasConflicts( List<Section> sections, Section newSection ){
		for( Section section : sections ){
			if( controller.conflicts.hasConflicts(newSection, section)){
				return true;
			}
		}
		return false;
	}
	
	public HashMap<Term, List<TimeCell>> getTimeConflicts(Section section) 
	{
		List<Term> offeredTerms = section.getTerms();
		HashMap<Term, List<TimeCell>> conflicts = new HashMap<Term, List<TimeCell>>();
		// For each term
		for(Term t : offeredTerms)
		{
			List<TimeCell> termConflicts = new ArrayList<TimeCell>();
			conflicts.put(t, termConflicts);
			// For each period
			for(Period p : section.periods)
			{
				// For each day
				for(DayOfWeek d : p.days)
				{
					//for the purposes of resolving conflicts, all periods must start on :00 or :30
					//if they don't, adjust the start times to reflect the blocks they fall in:
					
					int newStartMinutes;
					if(p.startTime.minutes%30 != 0) {    //if start time IS NOT on :00 or :30
						if(p.startTime.minutes-30 < 0) {   //if start time is between :00 and :30
							newStartMinutes = 0;
						}else {   //if start time is between :30 and :60
							newStartMinutes = 30;
						}
					}else {
						newStartMinutes = p.startTime.minutes;
					}
					//Time periodTime = new Time(p.startTime.hour, p.startTime.minutes);
					Time periodTime = new Time(p.startTime.hour, newStartMinutes);
					List<Time> chosenTimes = controller.studentTermTimes.getTimesForTerm(t).getTimes(d);
					// For each time cell
					while(periodTime.compareTo(p.endTime) < 0)
					{
						//System.out.println(periodTime.toString());
						if(!(chosenTimes.contains(periodTime)))
						{
							//System.out.println("CONFLICT");
							Time conflict = new Time(periodTime.hour, periodTime.minutes);
							if(!(termConflicts.contains(conflict)))
							{
								TimeCell full_conflict = new TimeCell(conflict, d);
								termConflicts.add(full_conflict);
							}
						}
						periodTime.increment(0, 60 / TimeCell.CELLS_PER_HOUR);
					}
				}	
			}
		}
		return conflicts;
	}

} 
