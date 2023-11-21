package be.nabu.eai.module.tracer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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
import be.nabu.eai.repository.RepositoryThreadFactory;
import be.nabu.eai.repository.api.ContainerArtifact;
import be.nabu.eai.repository.api.Entry;
import be.nabu.eai.server.ServerConnection;
import be.nabu.eai.server.CollaborationListener.User;
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
import be.nabu.libs.http.server.websockets.api.OpCode;
import be.nabu.libs.http.server.websockets.api.WebSocketMessage;
import be.nabu.libs.http.server.websockets.api.WebSocketRequest;
import be.nabu.libs.http.server.websockets.impl.WebSocketRequestParserFactory;
import be.nabu.libs.nio.api.StandardizedMessagePipeline;
import be.nabu.libs.nio.api.events.ConnectionEvent;
import be.nabu.libs.nio.api.events.ConnectionEvent.ConnectionState;
import be.nabu.libs.nio.impl.NIOClientImpl;
import be.nabu.libs.property.api.Property;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.services.api.DefinedService;
import be.nabu.libs.services.api.Service;
import be.nabu.libs.services.vm.api.Step;
import be.nabu.libs.services.vm.api.StepGroup;
import be.nabu.libs.services.vm.api.VMService;
import be.nabu.libs.types.ComplexContentWrapperFactory;
import be.nabu.libs.types.DefinedTypeResolverFactory;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedType;
import be.nabu.libs.types.base.SimpleElementImpl;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.binding.json.JSONBinding;
import be.nabu.libs.types.binding.xml.XMLBinding;
import be.nabu.libs.types.java.BeanInstance;
import be.nabu.libs.types.java.BeanResolver;
import be.nabu.libs.types.map.MapTypeGenerator;
import be.nabu.libs.types.structure.Structure;
import be.nabu.libs.types.structure.StructureInstance;
import be.nabu.utils.io.IOUtils;

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
						stopTrace(serviceId);
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
							TraceMessage message = TracerUtils.unmarshal(event.getData());
							// we ignore heartbeats, they are simply to keep the websocket connection alive!
							if (TraceType.HEARTBEAT.equals(message.getType())) {
								return;
							}
							String id = serviceId + " (" + message.getTraceId() + ")";
							NodeContainer container = MainController.getInstance().getContainer(id);
							if (container == null) {
								AnchorPane pane = new AnchorPane();
								VBox box = new VBox();
								
								TextField search = new TextField();
								search.setPromptText("Search");
								search.setMinWidth(300);
								HBox searchBox = new HBox();
								Label searchLabel = new Label("Search: ");
								searchLabel.setPadding(new Insets(4, 10, 0, 5));
								searchBox.setPadding(new Insets(10));
								searchBox.setAlignment(Pos.TOP_RIGHT);
								searchBox.getChildren().addAll(search);
								HBox.setHgrow(searchBox, Priority.ALWAYS);
								box.getChildren().add(searchBox);
								
								pane.getChildren().add(box);
								
								ScrollPane scroll = new ScrollPane();
								container = MainController.getInstance().newContainer(id, pane);
								Tree<TraceMessage> requestTree = new Tree<TraceMessage>(new CellFactory());
								pane.setUserData(requestTree);
								requestTree.getStyleClass().addAll("small", "tree");
								scroll.setContent(requestTree);
								scroll.getStyleClass().add("scroll");
								AnchorPane.setLeftAnchor(box, 0d);
								AnchorPane.setRightAnchor(box, 0d);
								AnchorPane.setBottomAnchor(box, 0d);
								AnchorPane.setTopAnchor(box, 0d);
								scroll.setFitToWidth(true);
								
								box.getChildren().add(scroll);
								VBox.setVgrow(scroll, Priority.ALWAYS);
								VBox.setVgrow(searchBox, Priority.NEVER);
								
								search.textProperty().addListener(new ChangeListener<String>() {
									@Override
									public void changed(ObservableValue<? extends String> arg0, String arg1, String arg2) {
										unhighlight(requestTree);
										if (arg2 != null && !arg2.trim().isEmpty()) {
											highlight(requestTree, arg2);
										}
									}
								});
								
//								pane.prefWidthProperty().bind(scroll.widthProperty());
								requestTree.rootProperty().set(new TraceTreeItem(null, message));
								final NodeContainer finalContainer = container;
								container.getContent().focusedProperty().addListener(new ChangeListener<Boolean>() {
									@Override
									public void changed(ObservableValue<? extends Boolean> arg0, Boolean arg1, Boolean arg2) {
										if (arg2 != null && arg2) {
											finalContainer.setChanged(false);
										}
									}
								});
							}
							else {
								if (!container.isFocused()) {
									container.setChanged(true);
								}
								Tree<TraceMessage> requestTree = (Tree<TraceMessage>) container.getContent().getUserData();
								TraceTreeItem current = getCurrent(requestTree.rootProperty().get());
								if (current == null) {
									current = (TraceTreeItem) requestTree.rootProperty().get();
								}
								// it is the "end" message of whatever current step is ongoing
								if (message.getStopped() != null) {
									// inherit the input from the "start" if applicable
									message.setInput(current.itemProperty().get().getInput());
									current.itemProperty().set(message);
								}
								// if we have to add a report, add it to the current item
								else if (message.getReport() != null) {
									current.getChildren().add(new TraceTreeItem(current, message));
								}
								// else it's a new message, add it
								else {
									current.getChildren().add(new TraceTreeItem(current, message));
								}
							}
						}
						catch (Exception e) {
							e.printStackTrace();
							try {
								byte[] bytes = IOUtils.toBytes(IOUtils.wrap(event.getData()));
								System.out.println("Trace message that could not be parsed: " + new String(bytes));
							}
							catch (IOException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
							}
						}
					}
				});
				return null;
			}
		});
		
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (true) {
					try {
						heartbeat();
					}
					catch (Exception e) {
						e.printStackTrace();
					}
					try {
						// trigger a heartbeat message every minute (default timeouts are generally 2 or 5 minutes)
						Thread.sleep(1000 * 60);
					}
					catch (Exception e) {
						break;
					}
				}
			}
		});
		thread.setDaemon(true);
		thread.setName("trace-heartbeat");
		initializeTraceTab(thread);
	}
	
	public static class Tracer {
		private String service;
		private Date since;
		public String getService() {
			return service;
		}
		public void setService(String service) {
			this.service = service;
		}
		public Date getSince() {
			return since;
		}
		public void setSince(Date since) {
			this.since = since;
		}
	}
	
	private static void initializeTraceTab(Thread thread) {
		// make sure we are on the fx thread
		Platform.runLater(new Runnable() {
			@Override
			public void run() {
				try {
					TabPane tabMisc = MainController.getInstance().getTabMisc();
					Tab tab = new Tab("Traces");
					tab.setId("traces");
					tabMisc.getTabs().add(tab);
					
					lstTracer = new ListView<Tracer>();
					lstTracer.setCellFactory(new Callback<ListView<Tracer>, ListCell<Tracer>>() {
						@Override 
						public ListCell<Tracer> call(ListView<Tracer> list) {
							return new ListCell<Tracer>() {
								@Override
								protected void updateItem(Tracer arg0, boolean empty) {
									super.updateItem(arg0, empty);
									// we always set an empty text, we use graphics to display more complex stuff
									setText(null);
									if (arg0 == null || empty) {
										setGraphic(null);
									}
									if (arg0 != null) {
										HBox box = new HBox();
										Label label = new Label(arg0.getService());
										label.setPadding(new Insets(10, 10, 10, 10));
										SimpleDateFormat simpleDateFormat = new SimpleDateFormat("MMM dd, HH:mm:ss");
										Label date = new Label(simpleDateFormat.format(arg0.getSince()));
										date.setStyle("-fx-text-fill: #aaa");
										date.setPadding(new Insets(10, 10, 10, 0));
										Pane filler = new Pane();
										Button stop = new Button("Stop");
										stop.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
											@Override
											public void handle(ActionEvent event) {
												stopTrace(arg0.getService());
											}
										});
										box.getChildren().addAll(label, date, filler, stop);
										HBox.setHgrow(filler, Priority.ALWAYS);
										setGraphic(box);
									}
								}
							};
						}
					});
					tab.setContent(lstTracer);
					// we only start the thread if we have the dedicated tab
					// if you have an old developer, this thread won't start, that's by design
					// persistent trace modes are too invisible without the tab and too hard to track down
					thread.start();
				}
				// this will fail on older developers as they are missing the getTabMisc() call!
				catch (Throwable e) {
					System.out.println("Trace tab not supported by this developer version");
				}
			}
		});
	}
	
	private static void highlight(Tree<TraceMessage> tree, String text) {
		highlight(tree.getRootCell(), text);
	}
	
	private static void highlight(TreeCell<TraceMessage> cell, String text) {
		cell.getCellValue().getNode().getStyleClass().remove("highlightedStep");
		TraceMessage traceMessage = cell.getItem().itemProperty().get();
		if (text != null && !text.trim().isEmpty()) {
			Step step = ((TraceTreeItem) cell.getItem()).getStep();
			boolean matches = false;
			if (step != null && VMServiceGUIManager.matches(step, text, false)) {
				matches = true;
			}
			else if (step == null) {
				if (traceMessage.getReport() != null && traceMessage.getReport().toLowerCase().contains(text.toLowerCase())) {
					matches = true;
				}
				else if (traceMessage.getServiceId() != null && traceMessage.getServiceId().toLowerCase().contains(text.toLowerCase())) {
					matches = true;
				}
				else if (traceMessage.getInput() != null && traceMessage.getInput().toLowerCase().contains(text.toLowerCase())) {
					matches = true;
				}
				else if (traceMessage.getOutput() != null && traceMessage.getOutput().toLowerCase().contains(text.toLowerCase())) {
					matches = true;
				}
			}
			if (cell.getChildren() != null && !cell.getChildren().isEmpty()) {
				for (TreeCell<TraceMessage> child : cell.getChildren()) {
					highlight(child, text);
				}
			}
			// children but none of them are shown in the tree (e.g. a match in a link inside a map step)
			else if (text != null && !text.trim().isEmpty() && step instanceof StepGroup && VMServiceGUIManager.matches(step, text, true)) {
				matches = true;
			}
			
			if (matches) {
				if (!cell.getCellValue().getNode().getStyleClass().contains("highlightedStep")) {
					cell.getCellValue().getNode().getStyleClass().add("highlightedStep");
				}
				cell.show();
			}
		}
	}
	
	private static void unhighlight(Tree<TraceMessage> tree) {
		unhighlight(tree.getRootCell());
	}
	
	private static void unhighlight(TreeCell<TraceMessage> cell) {
		cell.getCellValue().getNode().getStyleClass().remove("highlightedStep");
		// don't collapse the root but collapse everything else so we only have expanded that which has a match afterwards
		if (cell.getParent() != null && cell.expandedProperty().get()) {
			cell.expandedProperty().set(false);
		}
		if (cell.getChildren() != null && !cell.getChildren().isEmpty()) {
			for (TreeCell<TraceMessage> child : cell.getChildren()) {
				unhighlight(child);
			}
		}
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
				private ComplexContent dynamicParse(String message) throws IOException, ParseException {
					ComplexContent content;
					JSONBinding binding = new JSONBinding(new MapTypeGenerator(false), Charset.forName("UTF-8"));
					binding.setAllowDynamicElements(true);
					binding.setAddDynamicElementDefinitions(true);
					binding.setIgnoreRootIfArrayWrapper(true);
					content = binding.unmarshal(new ByteArrayInputStream(message.getBytes(Charset.forName("UTF-8"))), new Window[0]);
					return content;
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
							@SuppressWarnings({ "unchecked" })
							@Override
							public void handle(ActionEvent event) {
								try {
									ComplexContent content;
									// it was some undefined type, we do a parse without specific type
									if (message.getReportType() == null || "java.lang.Object".equals(message.getReportType())) {
										content = dynamicParse(message.getReport());
									}
									else {
										DefinedType resolvedType = DefinedTypeResolverFactory.getInstance().getResolver().resolve(message.getReportType());
										// it might be expressed in a type you don't know
										if (resolvedType == null) {
											TraceReportString reportString = new TraceReportString();
											reportString.setReport(message.getReport());
											content = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(reportString);
										}
										else {
											JSONBinding binding = new JSONBinding((ComplexType) resolvedType, Charset.forName("UTF-8"));
											content = binding.unmarshal(new ByteArrayInputStream(message.getReport().getBytes(Charset.forName("UTF-8"))), new Window[0]);
										}
									}
									MainController.getInstance().showContent(content);
									
//									Set<Property<?>> properties = getPropertiesFor(message.getReportType());
//									if (properties != null && !properties.isEmpty()) {
										
//										JAXBContext context = JAXBContext.newInstance(MainController.getInstance().getRepository().getClassLoader().loadClass(message.getReportType()));
//										Object unmarshal = context.createUnmarshaller().unmarshal(new StringReader(message.getReport()));
//										BeanInstance instance = new BeanInstance(unmarshal);
//										MainController.getInstance().showContent(instance);
										
//										List<Value<?>> values = new ArrayList<Value<?>>();
//										for (Property<?> property : properties) {
//											values.add(new ValueImpl(property, instance.get(property.getName())));
//										}
//										EAIDeveloperUtils.buildPopup(MainController.getInstance(), new SimplePropertyUpdater(true, properties, values.toArray(new Value[values.size()])), "Report", null);
//									}
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
								boolean displayed = false;
								// we first want to try a defined parsed, this gives us a lot more information about data types, formatting etc
								try {
									DefinedService service = ((TraceTreeItem) cell.get().getItem()).getService();
									if (service != null) {
//										XMLBinding binding = new XMLBinding(service.getServiceInterface().getInputDefinition(), Charset.forName("UTF-8"));
//										binding.setIgnoreUndefined(true);
										JSONBinding binding = new JSONBinding(service.getServiceInterface().getInputDefinition(), Charset.forName("UTF-8"));
										binding.setIgnoreUnknownElements(true);
										ComplexContent unmarshal = binding.unmarshal(new ByteArrayInputStream(message.getInput().getBytes("UTF-8")), new Window[0]);
										MainController.getInstance().showContent(unmarshal);
										displayed = true;
									}
								}
								catch(Exception e) {
									// ignore
								}
								if (!displayed) {
									try {
										MainController.getInstance().showContent(dynamicParse(message.getInput()));
									}
									catch (Exception f) {
										MainController.getInstance().showText(message.getOutput());
										f.printStackTrace();
									}
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
								boolean displayed = false;
								try {
									DefinedService service = ((TraceTreeItem) cell.get().getItem()).getService();
									if (service != null) {
//										XMLBinding binding = new XMLBinding(service.getServiceInterface().getOutputDefinition(), Charset.forName("UTF-8"));
										JSONBinding binding = new JSONBinding(service.getServiceInterface().getOutputDefinition(), Charset.forName("UTF-8"));
										ComplexContent unmarshal = binding.unmarshal(new ByteArrayInputStream(message.getOutput().getBytes("UTF-8")), new Window[0]);
										MainController.getInstance().showContent(unmarshal);
										displayed = true;
									}
								}
								catch (Exception e) {
									// ignore
								}
								if (!displayed) {
									try {
										MainController.getInstance().showContent(dynamicParse(message.getOutput()));
									}
									catch (Exception f) {
										MainController.getInstance().showText(message.getOutput());
										f.printStackTrace();
									}
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
					if (message.getServiceId() != null && message.getType() == TraceType.SERVICE) {
						Button showService = new Button();
						showService.setGraphic(MainController.loadFixedSizeGraphic("right-chevron.png", 12));
						HBox.setMargin(showService, new Insets(0, 0, 0, 5));
						showService.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
							@Override
							public void handle(ActionEvent arg0) {
								MainController.getInstance().open(message.getServiceId());
							}
						});
						box.getChildren().add(showService);
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
	
	private static void heartbeat() {
		// take clone for concurrent access issues
		Map<String, NIOHTTPClient> clientsClone = new HashMap<String, NIOHTTPClient>(clients);
		byte[] byteArray = "heartbeat".getBytes();
		WebSocketMessage message = WebSocketUtils.newMessage(OpCode.TEXT, true, byteArray.length, IOUtils.wrap(byteArray, true));
		for (NIOHTTPClient client : clientsClone.values()) {
			NIOClientImpl nioClient = ((NIOHTTPClientImpl) client).getNIOClient();
			// get all websocket pipelines
			List<StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage>> websocketPipelines = WebSocketUtils.getWebsocketPipelines(nioClient, null);
			for (StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage> pipeline : websocketPipelines) {
				pipeline.getResponseQueue().add(message);
			}
		}
	}
	
	private static void stopTrace(String serviceId) {
		synchronized(clients) {
			NIOHTTPClient client = clients.get(serviceId);
			if (client != null) {
				try {
					client.close();
				}
				catch (IOException e) {
					// can't really do anything
				}
				clients.remove(serviceId);
			}
		}
		Platform.runLater(new Runnable() {
			@Override
			public void run() {
				if (lstTracer != null) {
					synchronized(lstTracer) {
						Iterator<Tracer> iterator = lstTracer.getItems().iterator();
						while (iterator.hasNext()) {
							Tracer next = iterator.next();
							if (next.getService().equals(serviceId)) {
								iterator.remove();
							}
						}
					}
					updateTabCount();
				}
			}
		});
	}
	
	private static Map<String, NIOHTTPClient> clients = new HashMap<String, NIOHTTPClient>();
	private static ListView<Tracer> lstTracer;
	
	@Override
	public MenuItem getContext(Entry entry) {
		if (entry.isNode() && DefinedService.class.isAssignableFrom(entry.getNode().getArtifactClass())) {
			if (clients.containsKey(entry.getId())) {
				MenuItem item = new MenuItem("Stop Trace");
				item.addEventHandler(ActionEvent.ANY, new EventHandler<ActionEvent>() {
					@Override
					public void handle(ActionEvent arg0) {
						stopTrace(entry.getId());
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
									Tracer tracer = new Tracer();
									tracer.setService(entry.getId());
									tracer.setSince(new Date());
									Platform.runLater(new Runnable() {
										@Override
										public void run() {
											if (lstTracer != null) {
												synchronized(lstTracer) {
													lstTracer.getItems().add(tracer);
												}
												updateTabCount();
											}
										}
									});
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
	
	private static void updateTabCount() {
		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				TabPane tabMisc = MainController.getInstance().getTabMisc();
				for (Tab tab : tabMisc.getTabs()) {
					if (tab.getId() != null && tab.getId().equals("traces")) {
						int size = lstTracer.getItems().size();
						if (size == 0) {
							tab.setText("Traces");
						}
						else {
							tab.setText("Traces (" + size + ")");
						}
					}
				}
			}
		};
		if (Platform.isFxApplicationThread()) {
			runnable.run();
		}
		else {
			Platform.runLater(runnable);
		}
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
