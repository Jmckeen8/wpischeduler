package edu.wpi.scheduler.client;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Style.Position;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.InlineHTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import edu.wpi.scheduler.client.controller.StudentSchedule;
import edu.wpi.scheduler.client.tabs.TabList;
import edu.wpi.scheduler.shared.model.ScheduleDB;

public class MainView extends Composite {

	private static MainViewUiBinder uiBinder = GWT
			.create(MainViewUiBinder.class);

	interface MainViewUiBinder extends UiBinder<Widget, MainView> {
	}
	
	@UiField
	DockLayoutPanel layoutPanel;
	
	@UiField
	SimplePanel bodyPanel;
	
	@UiField(provided=true)
	TabList tabList;
	
	@UiField
	Label yearLabel;
	
	@UiField
	InlineHTML oldSchedLink;
	
	@UiField
	Label updatedLabel;
	

	public MainView( final StudentSchedule studentSchedule, ScheduleDB db) {
		tabList = new TabList(this, studentSchedule);
		
		initWidget(uiBinder.createAndBindUi(this));
		
		//layoutPanel.add( new CourseSelectorView(studentSchedule)  );
		bodyPanel.add( tabList.getHomeView() );
		
		
		getElement().getStyle().setLeft(0, Unit.PX);
		getElement().getStyle().setRight(0, Unit.PX);
		getElement().getStyle().setTop(0, Unit.PX);
		getElement().getStyle().setBottom(0, Unit.PX);
		getElement().getStyle().setPosition(Position.ABSOLUTE);
		
		//read year header from "yearHeader.txt"
		try {
			new RequestBuilder(RequestBuilder.GET, "yearHeader.txt").sendRequest("", new RequestCallback() {
				@Override
				public void onResponseReceived(Request req, Response resp) {
					String text = resp.getText();
					
					// first line is year. second line is true or false: whether to display oldSchedLink
					String[] splitText = text.split("\n");
					
					//set yearLabel from first line
					yearLabel.setText(splitText[0]);
					
					//convert second line to boolean
					boolean showOldSchedLink = Boolean.parseBoolean(splitText[1]);
					
					//if true, display the label with oldSchedLink
					if (showOldSchedLink) {
						oldSchedLink.setHTML("Looking for this year's schedule? <a style='color:white;text-decoration:underline;' href='/old'>Click here.</a>");
					}
				}
				
				@Override
				public void onError(Request res, Throwable throwable) {
					Logger logger = Logger.getLogger("logger");
					logger.log(Level.SEVERE, "Unable to read yearHeader.txt");
				}
			});
		} catch (RequestException e) {
			Logger logger = Logger.getLogger("logger");
			logger.log(Level.SEVERE, "Unable to request yearHeader.txt");
		}
		
		String refreshTimestamp = "Schedule Data Refreshed: " + db.generated;
		
		updatedLabel.setText(refreshTimestamp);
		
	}

	public void setBody(Widget body) {
		bodyPanel.remove(bodyPanel.getWidget());
		bodyPanel.add(body);
	}
	

}
