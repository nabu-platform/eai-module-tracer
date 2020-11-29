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

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Callback;

import javax.xml.bind.JAXBContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.developer.MainController;
import be.nabu.eai.developer.api.EntryContextMenuProvider;
import be.nabu.eai.developer.api.NodeContainer;
import be.nabu.eai.developer.managers.base.BaseConfigurationGUIManager;
import be.nabu.eai.developer.managers.util.SimplePropertyUpdater;
import be.nabu.eai.developer.util.EAIDeveloperUtils;
import be.nabu.eai.module.services.vm.StepFactory;
import be.nabu.eai.module.services.vm.VMServiceGUIManager;
import be.nabu.eai.module.tracer.TracerListener.TraceMessage;
import be.nabu.eai.module.tracer.TracerListener.TraceMessage.TraceType;
import be.nabu.eai.repository.RepositoryThreadFactory;
import be.nabu.eai.repository.api.ContainerArtifact;
import be.nabu.eai.repository.api.Entry;
import be.nabu.eai.server.ServerConnection;
import be.nabu.jfx.control.tree.Tree;
import be.nabu.jfx.control.tree.TreeCell;
import be.nabu.jfx.control.tree.TreeCellValue;
import be.nabu.jfx.control.tree.TreeItem;
import be.nabu.libs.artifacts.api.Artifact;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.impl.EventDispatcherImpl;
import be.nabu.libs.http.api.client.NIOHTTPClient;
import be.nabu.libs.http.client.SPIAuthenticationHandler;
import be.nabu.libs.http.client.nio.NIOHTTPClientImpl;
import be.nabu.libs.http.client.websocket.WebSocketClient;
import be.nabu.libs.http.core.CustomCookieStore;
import be.nabu.libs.http.server.nio.MemoryMessageDataProvider;
import be.nabu.libs.http.server.websockets.WebAuthorizationType;
import be.nabu.libs.http.server.websockets.WebSocketUtils;
import be.nabu.libs.http.server.websockets.api.WebSocketMessage;
import be.nabu.libs.http.server.websockets.api.WebSocketRequest;
import be.nabu.libs.http.server.websockets.impl.WebSocketRequestParserFactory;
import be.nabu.libs.nio.api.events.ConnectionEvent;
import be.nabu.libs.nio.api.events.ConnectionEvent.ConnectionState;
import be.nabu.libs.property.api.Property;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.services.api.DefinedService;
import be.nabu.libs.services.api.Service;
import be.nabu.libs.services.vm.api.Step;
import be.nabu.libs.services.vm.api.StepGroup;
import be.nabu.libs.services.vm.api.VMService;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.base.SimpleElementImpl;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.binding.xml.XMLBinding;
import be.nabu.libs.types.java.BeanInstance;
import be.nabu.libs.types.java.BeanResolver;
import be.nabu.libs.types.structure.Structure;
import be.nabu.libs.types.structure.StructureInstance;

public class TracerContextMenu implements EntryContextMenuProvider {

	private Logger logger = LoggerFactory.getLogger(getClass());
	private static EventDispatcher dispatcher = new EventDispatcherImpl();
	private static Map<String, WebSocketClient> websocketClients = new HashMap<String, WebSocketClient>();
	
	static {
		dispatcher.subscribe(ConnectionEvent.class, new be.nabu.libs.events.api.EventHandler<ConnectionEvent, Void>() {
			@Override
			public Void handle(ConnectionEvent event) {
				if (ConnectionState.CLOSED.equals(event.getState())) {
					WebSocketRequestParserFactory parserFactory = WebSocketUtils.getParserFactory(event.getPipeline());
					if (parserFactory != null) {
						String serviceId = parserFactory.getPath().substring("/trace/".length());
						synchronized(clients) {
							NIOHTTPClient client = clients.get(serviceId);
							if (client != null) {
								try {
									client.close();
								}
								catch (IOException e) {
									// can't really do much
								}
								finally {
									clients.remove(serviceId);
								}
							}
						}
					}
				}
				return null;
			}
		});
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
							NodeContainer container = MainController.getInstance().getContainer(id);
							if (container == null) {
								AnchorPane pane = new AnchorPane();
								ScrollPane scroll = new ScrollPane();
								container = MainController.getInstance().newContainer(id, scroll);
								Tree<TraceMessage> requestTree = new Tree<TraceMessage>(new CellFactory());
								requestTree.getStyleClass().addAll("small", "tree");
								pane.getChildren().add(requestTree);
								scroll.setContent(pane);
								AnchorPane.setLeftAnchor(requestTree, 0d);
								AnchorPane.setRightAnchor(requestTree, 0d);
								AnchorPane.setBottomAnchor(requestTree, 0d);
								AnchorPane.setTopAnchor(requestTree, 0d);
								pane.prefWidthProperty().bind(scroll.widthProperty());
								requestTree.rootProperty().set(new TraceTreeItem(null, message));
							}
							else {
								if (!container.isFocused()) {
									container.setChanged(true);
								}
								Tree<TraceMessage> requestTree = (Tree<TraceMessage>) ((AnchorPane) ((ScrollPane) container.getContent()).getContent()).getChildren().get(0);
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
					box.setAlignment(Pos.CENTER_LEFT);
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
					Node node;
					if (((TraceTreeItem) item).getStep() != null) {
						node = new HBox();
						StepFactory.StepCell.drawStep(((TraceTreeItem) item).getStep(), (HBox) node);
					}
					else {
						node = new Label(item.getName());
					}
					box.getChildren().add(node);
					if (message.getReport() != null) {
						Button showReport = new Button("Show");
						showReport.getStyleClass().add("small-button");
						HBox.setMargin(showReport, new Insets(0, 0, 0, 5));
						showReport.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
							@SuppressWarnings({ "rawtypes", "unchecked" })
							@Override
							public void handle(ActionEvent event) {
								try {
									Set<Property<?>> properties = getPropertiesFor(message.getReportType());
									if (properties != null && !properties.isEmpty()) {
										JAXBContext context = JAXBContext.newInstance(MainController.getInstance().getRepository().getClassLoader().loadClass(message.getReportType()));
										Object unmarshal = context.createUnmarshaller().unmarshal(new StringReader(message.getReport()));
										BeanInstance instance = new BeanInstance(unmarshal);
										MainController.getInstance().showContent(instance);
//										List<Value<?>> values = new ArrayList<Value<?>>();
//										for (Property<?> property : properties) {
//											values.add(new ValueImpl(property, instance.get(property.getName())));
//										}
//										EAIDeveloperUtils.buildPopup(MainController.getInstance(), new SimplePropertyUpdater(true, properties, values.toArray(new Value[values.size()])), "Report", null);
									}
								}
								catch (Exception e) {
									e.printStackTrace();
									// we tried...
								}
							}
						});
						box.getChildren().add(showReport);
					}
					else if (((TraceTreeItem) item).service != null) {
						node.addEventHandler(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
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
					if (message.getInput() != null) {
						Button showInput = new Button("Input");
						showInput.getStyleClass().add("small-button");
						HBox.setMargin(showInput, new Insets(0, 0, 0, 5));
						showInput.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
							@Override
							public void handle(ActionEvent arg0) {
								try {
									DefinedService service = ((TraceTreeItem) cell.get().getItem()).getService();
									if (service != null) {
										XMLBinding binding = new XMLBinding(service.getServiceInterface().getInputDefinition(), Charset.forName("UTF-8"));
										binding.setIgnoreUndefined(true);
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
						showOutput.getStyleClass().add("small-button");
						HBox.setMargin(showOutput, new Insets(0, 0, 0, 5));
						showOutput.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
							@Override
							public void handle(ActionEvent arg0) {
								try {
									DefinedService service = ((TraceTreeItem) cell.get().getItem()).getService();
									boolean displayed = false;
									if (service != null) {
										XMLBinding binding = new XMLBinding(service.getServiceInterface().getOutputDefinition(), Charset.forName("UTF-8"));
										try {
											ComplexContent unmarshal = binding.unmarshal(new ByteArrayInputStream(message.getOutput().getBytes("UTF-8")), new Window[0]);
											MainController.getInstance().showContent(unmarshal);
											displayed = true;
										}
										catch (Exception e) {
											// ignore
										}
									}
									if (!displayed) {
										MainController.getInstance().showText(message.getOutput());
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
						Button showError = new Button("Exception");
						showError.getStyleClass().add("small-button");
						HBox.setMargin(showError, new Insets(0, 0, 0, 5));
						showError.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
							@Override
							public void handle(ActionEvent event) {
								Structure structure = new Structure();
								structure.setName("output");
								structure.add(new SimpleElementImpl<String>("exception", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(String.class), structure));
								StructureInstance instance = structure.newInstance();
								instance.set("exception", message.getException());
								MainController.getInstance().showContent(instance);
//								MainController.newTextContextMenu(new Label(item.getName()), message.getException());
							}
						});
						box.getChildren().add(showError);
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
	
	private static Map<String, NIOHTTPClient> clients = new HashMap<String, NIOHTTPClient>();
	
	@Override
	public MenuItem getContext(Entry entry) {
		if (entry.isNode() && DefinedService.class.isAssignableFrom(entry.getNode().getArtifactClass())) {
			if (clients.containsKey(entry.getId())) {
				MenuItem item = new MenuItem("Stop Trace");
				item.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
					@Override
					public void handle(ActionEvent arg0) {
						synchronized(clients) {
							NIOHTTPClient client = clients.get(entry.getId());
							if (client != null) {
								try {
									client.close();
								}
								catch (IOException e) {
									// can't really do anything
								}
								clients.remove(entry.getId());
							}
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
						synchronized(clients) {
							if (!clients.containsKey(entry.getId())) {
								ServerConnection server = MainController.getInstance().getServer();
								MemoryMessageDataProvider dataProvider = new MemoryMessageDataProvider();
								NIOHTTPClientImpl client = new NIOHTTPClientImpl(server.getContext(), 3, 3, 5, dispatcher, dataProvider, new CookieManager(new CustomCookieStore(), CookiePolicy.ACCEPT_ALL), new RepositoryThreadFactory(entry.getRepository()));
								WebSocketUtils.allowWebsockets(client, dataProvider);
								try {
									WebSocketUtils.upgrade(client, server.getContext(), server.getHost(), server.getPort(), "/trace/" + entry.getId(), (Token) server.getPrincipal(), dataProvider, dispatcher, new ArrayList<String>(), WebAuthorizationType.BASIC);
									clients.put(entry.getId(), client);
								}
								catch (Exception e) {
									logger.error("Can not start trace mode for: " + entry.getId(), e);
									try {
										client.close();
									}
									catch (Exception f) {
										// nothing to do
									}
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
	
	public MenuItem getContext2(Entry entry) {
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
			leaf.set(true);
			children.addListener(new ListChangeListener<TreeItem<TraceMessage>>() {
				@Override
				public void onChanged(Change<? extends TreeItem<TraceMessage>> c) {
					boolean stillLeaf = children.isEmpty();
					while (c.next()) {
						if (c.wasAdded()) {
							stillLeaf = false;
						}
					}
					leaf.set(stillLeaf);
				}
			});
			item.addListener(new ChangeListener<TraceMessage>() {
				@Override
				public void changed(ObservableValue<? extends TraceMessage> arg0, TraceMessage arg1, TraceMessage message) {
					try {
						HBox graphic = new HBox();
						graphic.setAlignment(Pos.CENTER);
						List<VMService> services = new ArrayList<VMService>();
						if (message.getServiceId() != null) {
							be.nabu.eai.repository.api.Node node = MainController.getInstance().getRepository().getNode(message.getServiceId());
							if (node == null && message.getServiceId().contains(":")) {
								node = MainController.getInstance().getRepository().getNode(message.getServiceId().split(":")[0]);
							}
							if (node == null) {
								logger.warn("Could not find service: " + message.getServiceId());
							}
							else if (!(node.getArtifact() instanceof Service)) {
								logger.warn("Not a service: " + message.getServiceId());
							}
							else {
								service = (DefinedService) node.getArtifact();
								if (service instanceof VMService) {
									services.add((VMService) service);
								}
								else if (service instanceof ContainerArtifact) {
									for (Artifact artifact : ((ContainerArtifact) service).getContainedArtifacts()) {
										if (artifact instanceof VMService) {
											services.add((VMService) artifact); 
										}
									}
								}
							}
						}
						if (message.getStepId() != null) {
							for (VMService service : services) {
								step = getStep(service.getRoot(), message.getStepId());
								if (step != null) {
									break;
								}
							}
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

		public Step getStep() {
			return step;
		}
		
	}
}
