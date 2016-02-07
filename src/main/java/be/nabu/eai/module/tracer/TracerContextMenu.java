package be.nabu.eai.module.tracer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.JAXBContext;

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
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Callback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.developer.MainController;
import be.nabu.eai.developer.api.EntryContextMenuProvider;
import be.nabu.eai.developer.managers.JDBCServiceGUIManager;
import be.nabu.eai.developer.managers.base.BaseConfigurationGUIManager;
import be.nabu.eai.developer.managers.util.SimplePropertyUpdater;
import be.nabu.eai.module.services.vm.VMServiceGUIManager;
import be.nabu.eai.module.tracer.TracerListener.TraceMessage;
import be.nabu.eai.module.tracer.TracerListener.TraceMessage.TraceType;
import be.nabu.eai.repository.api.Entry;
import be.nabu.eai.server.ServerConnection;
import be.nabu.jfx.control.tree.Tree;
import be.nabu.jfx.control.tree.TreeCell;
import be.nabu.jfx.control.tree.TreeCellValue;
import be.nabu.jfx.control.tree.TreeItem;
import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.impl.EventDispatcherImpl;
import be.nabu.libs.http.client.SPIAuthenticationHandler;
import be.nabu.libs.http.client.websocket.WebSocketClient;
import be.nabu.libs.http.core.CustomCookieStore;
import be.nabu.libs.http.server.nio.MemoryMessageDataProvider;
import be.nabu.libs.http.server.websockets.api.WebSocketMessage;
import be.nabu.libs.http.server.websockets.api.WebSocketRequest;
import be.nabu.libs.property.api.Property;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.services.api.DefinedService;
import be.nabu.libs.services.vm.api.Step;
import be.nabu.libs.services.vm.api.StepGroup;
import be.nabu.libs.services.vm.api.VMService;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.binding.xml.XMLBinding;
import be.nabu.libs.types.java.BeanInstance;

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
								Tree<TraceMessage> requestTree = new Tree<TraceMessage>(new CellFactory());
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
									// inherit the input from the "start" if applicable
									message.setInput(current.itemProperty().get().getInput());
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

	private static ThreadLocal<SimpleDateFormat> formatter = new ThreadLocal<SimpleDateFormat>();
	
	private static SimpleDateFormat getFormatter() {
		if (formatter.get() == null) {
			formatter.set(new SimpleDateFormat("HH:mm"));
		}
		return formatter.get();
	}
	
	public static class CellFactory implements Callback<TreeItem<TraceMessage>, TreeCellValue<TraceMessage>> {
		
		private static Map<String, Set<Property<?>>> properties = new HashMap<String, Set<Property<?>>>();
		
		public static Set<Property<?>> getPropertiesFor(String name) {
			if (!properties.containsKey(name)) {
				synchronized(properties) {
					if (!properties.containsKey(name)) {
						try {
							Class<?> loadClass = MainController.getInstance().getRepository().getClassLoader().loadClass(name);
							properties.put(name, new HashSet<Property<?>>(BaseConfigurationGUIManager.createProperties(loadClass)));
						}
						catch (Exception e) {
							e.printStackTrace();
							// we tried...
						}
					}
				}
			}
			return properties.get(name);
		}
		
		@Override
		public TreeCellValue<TraceMessage> call(TreeItem<TraceMessage> item) {
			return new TreeCellValue<TraceMessage>() {
				private ObjectProperty<TreeCell<TraceMessage>> cell = new SimpleObjectProperty<TreeCell<TraceMessage>>();
				private HBox box = new HBox();
				{
					cell.addListener(new ChangeListener<TreeCell<TraceMessage>>() {
						@Override
						public void changed(ObservableValue<? extends TreeCell<TraceMessage>> arg0, TreeCell<TraceMessage> arg1, TreeCell<TraceMessage> arg2) {
							arg2.getItem().itemProperty().addListener(new ChangeListener<TraceMessage>() {
								@Override
								public void changed(ObservableValue<? extends TraceMessage> arg0, TraceMessage arg1, TraceMessage arg2) {
									refresh(cell.get().getItem(), arg2);
								}
							});
						}
					});
				}
				@Override
				public Region getNode() {
					if (box.getChildren().isEmpty()) {
						refresh();
					}
					return box;
				}
				@Override
				public ObjectProperty<TreeCell<TraceMessage>> cellProperty() {
					return cell;
				}
				@Override
				public void refresh() {
					refresh(item, item.itemProperty().get());
				}
				
				private void refresh(TreeItem<TraceMessage> item, TraceMessage message) {
					box.getChildren().clear();
					// it is an action that has a start & stop timestamp
					if (message.getStarted() != null) {
						Label label = new Label(getFormatter().format(cell.get().getItem().itemProperty().get().getStarted()));
						label.setStyle("-fx-text-fill: #AAAAAA");
						// it is not stopped yet
						if (message.getStopped() != null) {
							label.setText(label.getText() + ": " + (message.getStopped().getTime() - message.getStarted().getTime()) + "ms");
						}
						label.setPadding(new Insets(0, 10, 0, 5));
						box.getChildren().add(label);
					}
					Label name = new Label(item.getName());
					if (message.getReport() != null) {
						name.addEventHandler(MouseEvent.MOUSE_CLICKED, new EventHandler<MouseEvent>() {
							@SuppressWarnings({ "rawtypes", "unchecked" })
							@Override
							public void handle(MouseEvent event) {
								if (event.getClickCount() == 2) {
									try {
										Set<Property<?>> properties = getPropertiesFor(message.getReportType());
										if (properties != null && !properties.isEmpty()) {
											JAXBContext context = JAXBContext.newInstance(MainController.getInstance().getRepository().getClassLoader().loadClass(message.getReportType()));
											Object unmarshal = context.createUnmarshaller().unmarshal(new StringReader(message.getReport()));
											BeanInstance instance = new BeanInstance(unmarshal);
											List<Value<?>> values = new ArrayList<Value<?>>();
											for (Property<?> property : properties) {
												values.add(new ValueImpl(property, instance.get(property.getName())));
											}
											JDBCServiceGUIManager.buildPopup(MainController.getInstance(), new SimplePropertyUpdater(false, properties, values.toArray(new Value[values.size()])), "Report", null);
										}
									}
									catch (Exception e) {
										e.printStackTrace();
										// we tried...
									}
								}
							}
						});
					}
					else if (((TraceTreeItem) item).service != null) {
						name.addEventHandler(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
							@Override
							public void handle(KeyEvent event) {
								if (event.getCode() == KeyCode.L && event.isControlDown()) {
									Tree<Entry> tree = MainController.getInstance().getTree();
									TreeItem<Entry> resolve = tree.resolve(((TraceTreeItem) item).service.getId().replace(".", "/"));
									if (resolve != null) {
										TreeCell<Entry> treeCell = tree.getTreeCell(resolve);
										treeCell.show();
										treeCell.select();
									}
									event.consume();
								}
							}
						});
					}
					box.getChildren().add(name);
					if (message.getInput() != null) {
						Button showInput = new Button("Input");
						showInput.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
							@Override
							public void handle(ActionEvent arg0) {
								try {
									DefinedService service = ((TraceTreeItem) cell.get().getItem()).getService();
									if (service != null) {
										XMLBinding binding = new XMLBinding(service.getServiceInterface().getInputDefinition(), Charset.forName("UTF-8"));
										ComplexContent unmarshal = binding.unmarshal(new ByteArrayInputStream(message.getInput().getBytes("UTF-8")), new Window[0]);
										MainController.getInstance().showContent(unmarshal);
									}
									else {
										TextArea area = new TextArea();
										area.setEditable(false);
										area.setText(message.getInput());
										MainController.getInstance().getAncPipeline().getChildren().clear();
										MainController.getInstance().getAncPipeline().getChildren().add(area);
									}
								}
								catch(Exception e) {
									e.printStackTrace();
								}
							}
						});
						box.getChildren().add(showInput);
					}
					if (message.getOutput() != null) {
						Button showOutput = new Button("Output");
						showOutput.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
							@Override
							public void handle(ActionEvent arg0) {
								try {
									DefinedService service = ((TraceTreeItem) cell.get().getItem()).getService();
									if (service != null) {
										XMLBinding binding = new XMLBinding(service.getServiceInterface().getOutputDefinition(), Charset.forName("UTF-8"));
										ComplexContent unmarshal = binding.unmarshal(new ByteArrayInputStream(message.getOutput().getBytes("UTF-8")), new Window[0]);
										MainController.getInstance().showContent(unmarshal);
									}
									else {
										TextArea area = new TextArea();
										area.setEditable(false);
										area.setText(message.getOutput());
										MainController.getInstance().getAncPipeline().getChildren().clear();
										MainController.getInstance().getAncPipeline().getChildren().add(area);
									}
								}
								catch(Exception e) {
									e.printStackTrace();
								}
							}
						});
						box.getChildren().add(showOutput);
					}
					if (message.getException() != null) {
						name.addEventHandler(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
							@Override
							public void handle(KeyEvent event) {
								MainController.newTextContextMenu(name, message.getException());
							}
						});
					}
				}
			};
		}
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
			// first clean up the websocketClients so the right menu shows the correct state
			synchronized(websocketClients) {
				Iterator<WebSocketClient> iterator = websocketClients.values().iterator();
				while(iterator.hasNext()) {
					if (iterator.next().isClosed()) {
						iterator.remove();
					}
				}
			}
			
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
						if (message.getStarted() != null && message.getStopped() == null) {
							graphic.getChildren().add(MainController.loadGraphic("types/optional.png"));
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

		public DefinedService getService() {
			return service;
		}
		
	}
}
