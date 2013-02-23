package edu.wpi.scheduler.client.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.user.client.Timer;

import edu.wpi.scheduler.client.controller.ProducerUpdateEvent.UpdateType;
import edu.wpi.scheduler.client.permutation.PermutationController;
import edu.wpi.scheduler.shared.model.Course;
import edu.wpi.scheduler.shared.model.DayOfWeek;
import edu.wpi.scheduler.shared.model.Period;
import edu.wpi.scheduler.shared.model.Section;
import edu.wpi.scheduler.shared.model.Term;
import edu.wpi.scheduler.shared.model.Time;

/**
 * Finds all conflicts between periods, and courses schedules. 
 * 
 * The procedure works as follows:
 * -Place all the sections in a tree, where each layer of the tree are all sections of a given course (exclude any courses with 0 sections)
 * -Traverse the tree using DFS
 * -Before adding each item of to the DFS, compare the item to all the elements in the DFS's queue, and check for conflicts.
 * -When we reach the end of the tree. (A section of each course is in the queue), and there are no conflicts, create a new schedule. 
 * @author Nican
 *
 */
public class ScheduleProducer {
	
	enum CourseRelation {
		COMPATIBLE,
		CONFLICT
	}
	
	public static class CoursePair {
		public final Course course1;
		public final Course course2;
		private CourseRelation relation = CourseRelation.CONFLICT;
		
		CoursePair( Course course1, Course course2 ){
			this.course1 = course1;
			this.course2 = course2;
		}
		
		public boolean isConflict(){
			return relation == CourseRelation.CONFLICT;
		}
		
		void markCompatible(){
			relation = CourseRelation.COMPATIBLE;
		}
		
		public boolean equals( Course course1, Course course2 ){
			return (this.course1.equals(course1) && this.course2.equals( course2 )) ||
					(this.course2.equals(course1) && this.course1.equals( course2 ));
		}
	}
	
	public static class TreeStateItem {
		public int id;
		public boolean hasConflict;
		
		public TreeStateItem(int id, boolean hasConflict){
			this.id = id;
			this.hasConflict = hasConflict;
		}
	}

	public static interface ProducerEventHandler extends EventHandler {
		public void onPermutationUpdated(UpdateType type);
	}

	/**
	 * List of sections that we are going to cross match to find conflicts and
	 * schedules
	 */
	public final ArrayList<List<Section>> producedSections = new ArrayList<List<Section>>();

	/**
	 * List of actual found schedule permutations
	 */
	private List<SchedulePermutation> permutations = new ArrayList<SchedulePermutation>();

	/**
	 * Current state of searching the producedSections tree (Using the
	 * LinkedList as a queue/FIFO)
	 */
	public final ArrayList<TreeStateItem> treeSearchState = new ArrayList<TreeStateItem>();
	
	public final ArrayList<CoursePair> courseRelations = new ArrayList<CoursePair>();

	/**
	 * Timer that will generate more schedules as needed
	 */
	Timer timer = new Timer() {
		@Override
		public void run() {
			generate();
		}
	};
	
	boolean active = false;
	public final PermutationController controller;

	public ScheduleProducer(PermutationController controller) {
		this.controller = controller;

		for (SectionProducer producer : controller.studentSchedule.sectionProducers) {
			List<Section> sections = producer.getSections();

			if (!sections.isEmpty())
				producedSections.add(sections);
		}
		
		Collections.sort(producedSections, new Comparator<List<Section>>() {

			@Override
			public int compare(List<Section> o1, List<Section> o2) {
				int s1 = o1.size();
				int s2 = o2.size();
				
				if( s1 == s2 ) return 0;				
				return s1 < s2 ? -1 : 1;
			}
		});

		
	}
	
	public void start(){
		treeSearchState.clear();
		permutations.clear();
		
		//Nothing to do here! We have no sections to produce schedules from
		if( producedSections.isEmpty() )
			return;
		
		// Start with the root node of the tree
		addState(0);

		// Start generating!
		timer.scheduleRepeating(10);
		active = true;
	}

	public List<SchedulePermutation> getPermutations() {
		return permutations;
	}

	/**
	 * Cancels the timer from generating any more schedules
	 */
	public void cancel() {
		if(!isActive())
			return;
		
		timer.cancel();
		active = false;
		
		controller.fireEvent(new ProducerUpdateEvent(UpdateType.FINISH));
	}
	
	public CoursePair getConflictCourse(){
		//We have a schedule! We do not have any conflicts!
		if (permutations.size() > 0 )
			return null;
		
		//Find a course pair that is under conflict
		for( CoursePair pair : courseRelations ){
			if( pair.isConflict() )
				return pair;
		}
		
		return null;		
	}
	
	public boolean isActive(){
		return active;
	}

	public void generate() {
		int i = 0;
		int oldSize = permutations.size();

		while (i < 20 && canGenerate()) {
			generateNext();
			i++;
		}
		
		if (oldSize != permutations.size())
			controller.fireEvent(new ProducerUpdateEvent(UpdateType.UPDATE));
		
		if (!canGenerate() || permutations.size() > 50000 )
			this.cancel();
	}

	protected boolean canGenerate() {
		return !treeSearchState.isEmpty() && isActive();
	}

	private void generateNext() {
		int treeSize = treeSearchState.size();

		// Can we move down the tree?
		if (treeSize < producedSections.size()) {
			
			// Move a step down and compare the current state
			addState(0);

			return;
		}

		// We are at the leaf of the tree, go one back and look for the next
		// section
		while (!treeSearchState.isEmpty()) {
			int lastId = treeSearchState.remove(treeSearchState.size() - 1).id;
			int newId = lastId + 1;
			int sectionsSize = producedSections.get(treeSearchState.size()).size();

			if (sectionsSize > newId && !hasStateConflicts()) {
				addState(newId);
				return;
			}
		}
	}
	
	public Section getSectionFromTree( int level ){
		return producedSections.get(level).get(treeSearchState.get(level).id);
	}
	
	private Map<Term, Integer> getTermCount(){
		Map<Term, Integer> count = new HashMap<Term, Integer>();
		
		for( Term term : Term.values() ){
			count.put(term, 0);
		}
		
		for (int i = 0; i < treeSearchState.size(); i++) {
			Section section = getSectionFromTree(i);
			
			for( Term term : section.getTerms() ){
				count.put(term, count.get(term) + 1);
			}
		}
		
		return count;
	}

	private void addState(int newId) {
		Section newSection = producedSections.get(treeSearchState.size()).get(newId);
		boolean hasConflicts = false;

		// Look at our current state, and see if we have any conflicts
		for (int i = 0; i < treeSearchState.size(); i++) {
			Section section = getSectionFromTree(i);
			
			hasConflicts = hasConflicts(newSection, section);
			
			updateCourseRelation( section.course, newSection.course, hasConflicts );

			if (hasConflicts) 
				break;
		}
		
		treeSearchState.add(new TreeStateItem( newId, hasConflicts ));


		// If we are at the leaf of the tree, and we do not have a conflict
		// We have a winner!
		if (treeSearchState.size() == producedSections.size() && !hasStateConflicts()) {
			Map<Term, Integer> termCount = getTermCount();
			
			for( Integer count : termCount.values() ){
				if( count > 4 )
					return;
			}		
			
			addStateToSchedules();
		}
	}
	
	public void updateCourseRelation(Course course1, Course course2, boolean hasConflicts ){
		CoursePair coursePair = null;
		
		for( CoursePair pair : courseRelations ){
			if( pair.equals( course1, course2 ) ){
				coursePair = pair;
				break;
			}			
		}
		
		if( coursePair == null ){
			coursePair = new CoursePair(course1, course2);
			courseRelations.add(coursePair);
		}
		
		if( !hasConflicts )
			coursePair.markCompatible();		
	}
	
	public boolean hasStateConflicts(){
		for( TreeStateItem item : treeSearchState )
			if( item.hasConflict == true )
				return true;
		
		return false;
	}

	private void addStateToSchedules() {
		SchedulePermutation schedule = new SchedulePermutation();

		for (int i = 0; i < treeSearchState.size(); i++) {
			Section section = producedSections.get(i).get(treeSearchState.get(i).id);

			schedule.sections.add(section);
		}

		permutations.add(schedule);
		
		if( permutations.size() == 1 ){
			//We are the first insert, make this the selected schedule
			controller.selectPermutation(schedule);
		}
	}

	public static boolean hasConflicts(Section newSection, Section section) {
		List<Term> newTerms = newSection.getTerms();
		List<Term> terms = section.getTerms();
		boolean conflictingTerms = false;

		// Checks if any terms exists in both sections
		for (Term term : terms) {
			if (newTerms.contains(term)) {
				conflictingTerms = true;
			}
		}		

		// The classes are not even in the same days of the year
		// There are no conflict here
		if (conflictingTerms == false)
			return false;

		for (Period period : section.periods) {
			for (Period newPeriod : newSection.periods) {
				if (hasConflitcs(period, newPeriod))
					return true;
			}
		}

		return false;

	}
	
	private static boolean containsAny( HashSet<DayOfWeek> days1, HashSet<DayOfWeek> days2 ){
		for( DayOfWeek day : days1 ){
			if( days2.contains(day)){
				return true;
			}
		}
		return false;
	}

	private static boolean hasConflitcs(Period period, Period newPeriod) {

		//No days in common, no conflicts		
		if(!containsAny(period.days, newPeriod.days))
			return false;

		Time periodStart = period.startTime;
		Time periodEnd = period.endTime;

		Time otherStart = newPeriod.startTime;
		Time otherEnd = newPeriod.endTime;

		return (otherStart.compareTo(periodStart) >= 0 && otherStart.compareTo(periodEnd) <= 0)
				|| (otherEnd.compareTo(periodStart) >= 0 && otherEnd.compareTo(periodEnd) <= 0)
				|| (otherStart.compareTo(periodStart) <= 0 && otherEnd.compareTo(periodEnd) >= 0);
	}

}
