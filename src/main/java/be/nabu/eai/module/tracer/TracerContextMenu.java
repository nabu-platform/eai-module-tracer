package be.nabu.eai.module.tracer;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.developer.MainController;
import be.nabu.eai.developer.ServerConnection;
import be.nabu.eai.developer.api.EntryContextMenuProvider;
import be.nabu.eai.developer.managers.VMServiceGUIManager;
import be.nabu.eai.module.tracer.TracerListener.TraceMessage;
import be.nabu.eai.module.tracer.TracerListener.TraceMessage.TraceType;
import be.nabu.eai.repository.api.Entry;
import be.nabu.jfx.control.tree.Tree;
import be.nabu.jfx.control.tree.TreeItem;
import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.impl.EventDispatcherImpl;
import be.nabu.libs.http.client.SPIAuthenticationHandler;
import be.nabu.libs.http.client.websocket.WebSocketClient;
import be.nabu.libs.http.core.CustomCookieStore;
import be.nabu.libs.http.server.nio.MemoryMessageDataProvider;
import be.nabu.libs.http.server.websockets.api.WebSocketMessage;
import be.nabu.libs.http.server.websockets.api.WebSocketRequest;
import be.nabu.libs.services.api.DefinedService;
import be.nabu.libs.services.vm.api.Step;
import be.nabu.libs.services.vm.api.StepGroup;
import be.nabu.libs.services.vm.api.VMService;

public class TracerContextMenu implements EntryContextMenuProvider {

	private static EventDispatcher dispatcher = new EventDispatcherImpl();
	private static Map<String, WebSocketClient> websocketClients = new HashMap<String, WebSocketClient>();
	
	static {
		dispatcher.subscribe(WebSocketRequest.class, new be.nabu.libs.events.api.EventHandler<WebSocketRequest, WebSocketMessage>() {
			
			@Override
			public WebSocketMessage handle(WebSocketRequest event) {
				Platform.runLater(new Runnable() {
					@SuppressWarnings("unchecked")
					public void run() {
						try {
							String serviceId = event.getPath().substring("/trace/".length());
							TraceMessage message = TraceMessage.unmarshal(event.getData());
							String id = serviceId + " (" + message.getTraceId() + ")";
							Tab tab = MainController.getInstance().getTab(id);
							if (tab == null) {
								final Tab newTab = MainController.getInstance().newTab(id);
								AnchorPane pane = new AnchorPane();
								newTab.setContent(pane);
								Tree<TraceMessage> requestTree = new Tree<TraceMessage>();
								pane.getChildren().add(requestTree);
								AnchorPane.setLeftAnchor(requestTree, 0d);
								AnchorPane.setRightAnchor(requestTree, 0d);
								requestTree.rootProperty().set(new TraceTreeItem(null, message));
								newTab.selectedProperty().addListener(new ChangeListener<Boolean>() {
									@Override
									public void changed(ObservableValue<? extends Boolean> arg0, Boolean arg1, Boolean arg2) {
										if (arg2) {
											if (newTab.getText().endsWith("*")) {
												newTab.setText(newTab.getText().replaceAll("[\\s]*\\*$", ""));
											}
										}
									}
								});
								tab = newTab;
							}
							else {
								if (!tab.isSelected()) {
									tab.setText(tab.getText() + " *");
								}
								Tree<TraceMessage> requestTree = (Tree<TraceMessage>) ((AnchorPane) tab.getContent()).getChildren().get(0);
								TraceTreeItem current = getCurrent(requestTree.rootProperty().get());
								if (current == null) {
									current = (TraceTreeItem) requestTree.rootProperty().get();
								}
								// if we have to add a report, add it to the current item
								if (message.getReport() != null) {
									current.getChildren().add(new TraceTreeItem(current, message));
								}
								// it is the "end" message of whatever current step is ongoing
								else if (message.getStopped() != null) {
									current.itemProperty().set(message);
								}
								// else it's a new message, add it
								else {
									current.getChildren().add(new TraceTreeItem(current, message));
								}
							}
						}
						catch (Exception e) {
							e.printStackTrace();
						}
					}
				});
				return null;
			}
		});
	}
	
	/**
	 * Finds the "current" step that is active
	 */
	private static TraceTreeItem getCurrent(TreeItem<TraceMessage> treeItem) {
		// go in reverse
		for (int i = treeItem.getChildren().size() - 1; i >= 0; i--) {
			// and go deep
			TreeItem<TraceMessage> child = treeItem.getChildren().get(i);
			TraceTreeItem current = getCurrent(child);
			if (current != null) {
				return current;
			}
			if (child.itemProperty().get().getReport() == null && child.itemProperty().get().getStopped() == null) {
				return (TraceTreeItem) child;
			}
		}
		return null;
	}
	
	@Override
	public MenuItem getContext(Entry entry) {
		// if it is a service, add the ability to set a trace on it
		if (entry.isNode() && DefinedService.class.isAssignableFrom(entry.getNode().getArtifactClass())) {
			if (websocketClients.containsKey(entry.getId())) {
				MenuItem item = new MenuItem("Stop Trace");
				item.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
					@Override
					public void handle(ActionEvent arg0) {
						synchronized(websocketClients) {
							WebSocketClient webSocketClient = websocketClients.get(entry.getId());
							if (webSocketClient != null) {
								try {
									webSocketClient.close();
								}
								catch (IOException e) {
									// can't really do anything
								}
							}
							websocketClients.remove(entry.getId());
						}
					}
				});
				return item;
			}
			else {
				MenuItem item = new MenuItem("Start Trace");
				item.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
					@Override
					public void handle(ActionEvent event) {
						if (!websocketClients.containsKey(entry.getId())) {
							synchronized(websocketClients) {
								if (!websocketClients.containsKey(entry.getId())) {
									ServerConnection server = MainController.getInstance().getServer();
									WebSocketClient client = WebSocketClient.connect(
										server.getContext(), 
										server.getHost(), 
										server.getPort(),
										"/trace/" + entry.getId(),
										// if nothing happens in an hour, the trace is cut
										60000*60*24,
										new SPIAuthenticationHandler(), 
										new CookieManager(new CustomCookieStore(), CookiePolicy.ACCEPT_ALL),
										server.getPrincipal(), 
										new ArrayList<String>(), 
										new MemoryMessageDataProvider(1024*1024*10), 
										dispatcher
									);
									websocketClients.put(entry.getId(), client);
									client.start();
								}
							}
						}
					}
				});
				return item;
			}
		}
		return null;
	}

	public static class TraceTreeItem implements TreeItem<TraceMessage> {

		private Logger logger = LoggerFactory.getLogger(getClass());
		private ObjectProperty<TraceMessage> item = new SimpleObjectProperty<TraceMessage>();
		private BooleanProperty editable = new SimpleBooleanProperty(false);
		private BooleanProperty leaf = new SimpleBooleanProperty();
		private ObjectProperty<Node> graphic = new SimpleObjectProperty<Node>();
		private ObservableList<TreeItem<TraceMessage>> children = FXCollections.observableArrayList();
		private DefinedService service;
		private Step step;
		private TraceTreeItem parent;
		private String name;
		
		public TraceTreeItem(TraceTreeItem parent, TraceMessage message) {
			this.parent = parent;
			item.addListener(new ChangeListener<TraceMessage>() {
				@Override
				public void changed(ObservableValue<? extends TraceMessage> arg0, TraceMessage arg1, TraceMessage message) {
					try {
						// only reports are actual leafs
						leaf.set(message.getReport() != null);
						HBox graphic = new HBox();
						if (message.getServiceId() != null) {
							service = (DefinedService) MainController.getInstance().getRepository().getNode(message.getServiceId()).getArtifact();
						}
						if (message.getStepId() != null && service instanceof VMService) {
							step = getStep(((VMService) service).getRoot(), message.getStepId());
						}
						if (TraceType.SERVICE.equals(message.getType())) {
							name = message.getServiceId();
							graphic.getChildren().add(MainController.loadGraphic("service.png"));
						}
						else if (TraceType.STEP.equals(message.getType()) && service instanceof VMService) {
							if (step == null) {
								name = "Unknown step (" + message.getStepId() + ")";
							}
							else {
								name = step.getClass().getSimpleName();
								if (step.getComment() != null) {
									name += ": " + step.getComment();
								}
							}
							graphic.getChildren().add(MainController.loadGraphic(VMServiceGUIManager.getIcon(step.getClass())));
						}
						else if (TraceType.REPORT.equals(message.getType())) {
							name = "Report: " + message.getReportType().replaceAll("^.*\\.([^.]+)$", "$1");
							graphic.getChildren().add(MainController.loadGraphic("types/string.gif"));
						}
						else {
							name = "Unknown type: " + message.getType();
						}
						if (message.getException() != null) {
							graphic.getChildren().add(MainController.loadGraphic("error.png"));
						}
						TraceTreeItem.this.graphic.set(graphic);
					}
					catch (Throwable e) {
						logger.error("Could not update item", e);
					}
				}
			});
			item.set(message);
		}
		
		private Step getStep(StepGroup group, String id) {
			for (Step step : group.getChildren()) {
				if (id.equals(step.getId())) {
					return step;
				}
				else if (step instanceof StepGroup) {
					Step potential = getStep((StepGroup) step, id);
					if (potential != null) {
						return potential;
					}
				}
			}
			return null;
		}
		
		@Override
		public void refresh() {
			// do nothing
		}

		@Override
		public BooleanProperty editableProperty() {
			return editable;
		}

		@Override
		public BooleanProperty leafProperty() {
			return leaf;
		}

		@Override
		public ObjectProperty<TraceMessage> itemProperty() {
			return item;
		}

		@Override
		public ObjectProperty<Node> graphicProperty() {
			return graphic;
		}

		@Override
		public ObservableList<TreeItem<TraceMessage>> getChildren() {
			return children;
		}

		@Override
		public TraceTreeItem getParent() {
			return parent;
		}

		@Override
		public String getName() {
			return name;
		}
		
	}
}
